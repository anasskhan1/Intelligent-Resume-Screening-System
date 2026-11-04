package com.system.resume.service;

import com.system.resume.dto.RefreshTokenResult;
import com.system.resume.exception.InvalidTokenException;
import com.system.resume.model.RefreshToken;
import com.system.resume.model.Role;
import com.system.resume.model.User;
import com.system.resume.repository.RefreshTokenRepository;
import com.system.resume.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenConcurrencyAndSafetyTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenFamilyRevocationService tokenFamilyRevocationService;

    private RefreshTokenService refreshTokenService;
    private User testUser;
    private LocalDateTime fixedNow;

    @BeforeEach
    void setUp() {
        Instant fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
        fixedNow = LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC);
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        refreshTokenService = new RefreshTokenService(
                refreshTokenRepository,
                userRepository,
                tokenFamilyRevocationService,
                fixedClock
        );

        testUser = new User("sarah@example.com", "hash", "Sarah", "Connor", true);
        testUser.setId(101L);
        testUser.setRoles(Set.of(new Role(1L, "ROLE_RECRUITER", "Recruiter", null)));
    }

    @Test
    @DisplayName("Concurrency: Simultaneous refresh requests using the same token cannot both succeed; losing request is rejected")
    void testSimultaneousRefresh_CannotBothSucceed_AndLosingRequestCannotIssueToken() throws Exception {
        String sharedRawToken = "shared_raw_token_for_concurrency";
        String tokenHash = RefreshTokenService.hashToken(sharedRawToken);
        String tokenFamily = "concurrency-family-1";

        // Shared mutable database state simulating a single row in MySQL
        RefreshToken persistentRow = new RefreshToken(tokenHash, tokenFamily, testUser, fixedNow.plusDays(5), "127.0.0.1");
        persistentRow.setId(1L);
        persistentRow.setIsRevoked(false);
        persistentRow.setCreatedAt(fixedNow.minusDays(1));

        // Reentrant lock simulating MySQL SELECT ... FOR UPDATE pessimistic write locking
        ReentrantLock dbRowLock = new ReentrantLock(true);

        when(refreshTokenRepository.findByTokenHashWithLock(tokenHash)).thenAnswer(invocation -> {
            dbRowLock.lock();
            // Return row representing current committed database state
            RefreshToken copy = new RefreshToken();
            copy.setId(persistentRow.getId());
            copy.setTokenHash(persistentRow.getTokenHash());
            copy.setTokenFamily(persistentRow.getTokenFamily());
            copy.setUser(persistentRow.getUser());
            copy.setIsRevoked(persistentRow.getIsRevoked());
            copy.setExpiresAt(persistentRow.getExpiresAt());
            copy.setCreatedAt(persistentRow.getCreatedAt());
            copy.setRevokedAt(persistentRow.getRevokedAt());
            copy.setReplacedBy(persistentRow.getReplacedBy());
            return Optional.of(copy);
        });

        when(refreshTokenRepository.findEarliestCreatedAtByTokenFamily(tokenFamily))
                .thenReturn(Optional.of(fixedNow.minusDays(1)));

        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken entity = invocation.getArgument(0);
            if (entity.getId() != null && entity.getId().equals(persistentRow.getId())) {
                persistentRow.setIsRevoked(entity.getIsRevoked());
                persistentRow.setRevokedAt(entity.getRevokedAt());
                persistentRow.setReplacedBy(entity.getReplacedBy());
                // Release row lock upon transaction commit of old token's revocation/replacement
                if (dbRowLock.isHeldByCurrentThread()) {
                    dbRowLock.unlock();
                }
            }
            return entity;
        });

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger reuseFailureCount = new AtomicInteger(0);
        AtomicBoolean losingThreadIssuedToken = new AtomicBoolean(false);

        for (int i = 0; i < threadCount; i++) {
            final String clientIp = "192.168.1." + (i + 1);
            executor.submit(() -> {
                try {
                    startGate.await(); // Synchronize thread start
                    RefreshTokenResult result = refreshTokenService.rotateRefreshToken(sharedRawToken, clientIp);
                    if (result != null && result.rawRefreshToken() != null) {
                        successCount.incrementAndGet();
                    }
                } catch (InvalidTokenException ex) {
                    if (ex.getMessage().contains("reuse of revoked token detected")) {
                        reuseFailureCount.incrementAndGet();
                    } else {
                        losingThreadIssuedToken.set(true); // Unexpected error
                    }
                } catch (Exception ex) {
                    losingThreadIssuedToken.set(true);
                } finally {
                    if (dbRowLock.isHeldByCurrentThread()) {
                        dbRowLock.unlock();
                    }
                    finishGate.countDown();
                }
            });
        }

        // Release both threads simultaneously
        startGate.countDown();
        assertTrue(finishGate.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly one thread succeeded; the losing thread was rejected via reuse detection
        assertEquals(1, successCount.get(), "Exactly one concurrent refresh request must succeed");
        assertEquals(1, reuseFailureCount.get(), "Losing request must be caught by reuse detection");
        assertFalse(losingThreadIssuedToken.get(), "Losing request must NEVER issue a replacement token");

        // The losing request triggered family-wide revocation
        verify(tokenFamilyRevocationService, atLeastOnce()).revokeEntireFamily(eq(tokenFamily), any());
    }

    @Test
    @DisplayName("Transaction safety: Token family revocation uses REQUIRES_NEW propagation to survive 401 exceptions")
    void testTokenFamilyRevocation_UsesRequiresNewPropagation() throws NoSuchMethodException {
        Method revokeMethod = TokenFamilyRevocationService.class.getMethod("revokeEntireFamily", String.class, LocalDateTime.class);
        Transactional annotation = revokeMethod.getAnnotation(Transactional.class);

        assertNotNull(annotation, "revokeEntireFamily must be annotated with @Transactional");
        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation(),
                "revokeEntireFamily MUST use Propagation.REQUIRES_NEW to commit independently of outer transaction rollbacks");
    }

    @Test
    @DisplayName("Token-family reuse: Family-wide revocation is committed even when rotateRefreshToken throws 401 exception")
    void testReuseDetection_CommitsRevocationBeforeThrowingException() {
        String rawToken = "stolen_compromised_token";
        String tokenHash = RefreshTokenService.hashToken(rawToken);
        String tokenFamily = "stolen-family-123";

        RefreshToken revokedToken = new RefreshToken(tokenHash, tokenFamily, testUser, fixedNow.plusDays(2));
        revokedToken.setIsRevoked(true);
        revokedToken.setRevokedAt(fixedNow.minusMinutes(5));

        when(refreshTokenRepository.findByTokenHashWithLock(tokenHash)).thenReturn(Optional.of(revokedToken));
        when(tokenFamilyRevocationService.revokeEntireFamily(eq(tokenFamily), eq(fixedNow))).thenReturn(3);

        // When reuse occurs, InvalidTokenException must be thrown
        InvalidTokenException exception = assertThrows(InvalidTokenException.class,
                () -> refreshTokenService.rotateRefreshToken(rawToken, "10.0.0.5"));

        assertEquals("Compromised token: reuse of revoked token detected", exception.getMessage());

        // Verify that the isolated REQUIRES_NEW family revocation was explicitly executed
        verify(tokenFamilyRevocationService, times(1)).revokeEntireFamily(eq(tokenFamily), eq(fixedNow));
    }
}


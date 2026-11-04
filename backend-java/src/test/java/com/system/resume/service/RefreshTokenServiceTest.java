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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    private RefreshTokenService refreshTokenService;
    private User testUser;
    private Instant fixedInstant;
    private LocalDateTime fixedNow;

    @BeforeEach
    void setUp() {
        fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
        fixedNow = LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC);
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        refreshTokenService = new RefreshTokenService(refreshTokenRepository, userRepository, fixedClock);

        Role roleRecruiter = new Role(1L, "ROLE_RECRUITER", "Recruiter", null);
        testUser = new User("sarah@example.com", "hashed_password", "Sarah", "Connor", true);
        testUser.setId(100L);
        testUser.setRoles(Set.of(roleRecruiter));
    }

    @Test
    @DisplayName("createRefreshToken should save SHA-256 hash and return raw opaque token with 7-day expiry")
    void testCreateRefreshToken_Success() {
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenResult result = refreshTokenService.createRefreshToken(testUser, "192.168.1.1");

        assertNotNull(result.rawRefreshToken());
        assertFalse(result.rawRefreshToken().isBlank());
        assertEquals(testUser, result.user());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertEquals(RefreshTokenService.hashToken(result.rawRefreshToken()), saved.getTokenHash());
        assertNotEquals(result.rawRefreshToken(), saved.getTokenHash());
        assertFalse(saved.getIsRevoked());
        assertEquals("192.168.1.1", saved.getClientIp());
        assertEquals(fixedNow.plus(Duration.ofDays(7)), saved.getExpiresAt());
        assertNotNull(saved.getTokenFamily());
    }

    @Test
    @DisplayName("rotateRefreshToken should revoke old token, record replacement, and return new token in same family")
    void testRotateRefreshToken_Success() {
        String oldRawToken = "initial_raw_token_value_for_testing";
        String oldHash = RefreshTokenService.hashToken(oldRawToken);
        String family = "family-12345";

        RefreshToken oldToken = new RefreshToken(oldHash, family, testUser, fixedNow.plusDays(5), "127.0.0.1");
        oldToken.setId(1L);
        oldToken.setIsRevoked(false);
        oldToken.setCreatedAt(fixedNow.minusDays(2));

        when(refreshTokenRepository.findByTokenHashWithLock(oldHash)).thenReturn(Optional.of(oldToken));
        when(refreshTokenRepository.findEarliestCreatedAtByTokenFamily(family)).thenReturn(Optional.of(fixedNow.minusDays(2)));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenResult result = refreshTokenService.rotateRefreshToken(oldRawToken, "127.0.0.1");

        assertNotNull(result.rawRefreshToken());
        assertNotEquals(oldRawToken, result.rawRefreshToken());
        assertEquals(testUser, result.user());

        // Verify old token was revoked
        assertTrue(oldToken.getIsRevoked());
        assertEquals(fixedNow, oldToken.getRevokedAt());
        assertNotNull(oldToken.getReplacedBy());

        // Verify new token is in same family with 7-day expiration capped at 30 days
        RefreshToken replacement = oldToken.getReplacedBy();
        assertEquals(family, replacement.getTokenFamily());
        assertEquals(fixedNow.plusDays(7), replacement.getExpiresAt());
    }

    @Test
    @DisplayName("rotateRefreshToken should detect reuse of revoked token and revoke entire token family")
    void testRotateRefreshToken_ReuseDetection_RevokesEntireFamily() {
        String compromisedRawToken = "compromised_stolen_token";
        String compromisedHash = RefreshTokenService.hashToken(compromisedRawToken);
        String family = "stolen-family-999";

        RefreshToken alreadyRevokedToken = new RefreshToken(compromisedHash, family, testUser, fixedNow.plusDays(3));
        alreadyRevokedToken.setIsRevoked(true);
        alreadyRevokedToken.setRevokedAt(fixedNow.minusHours(1));

        when(refreshTokenRepository.findByTokenHashWithLock(compromisedHash)).thenReturn(Optional.of(alreadyRevokedToken));

        InvalidTokenException ex = assertThrows(InvalidTokenException.class,
                () -> refreshTokenService.rotateRefreshToken(compromisedRawToken, "10.0.0.1"));

        assertTrue(ex.getMessage().contains("reuse of revoked token detected"));
        verify(refreshTokenRepository, times(1)).revokeByTokenFamily(eq(family), eq(fixedNow));
    }

    @Test
    @DisplayName("rotateRefreshToken should reject token that exceeded 7-day inactivity limit")
    void testRotateRefreshToken_InactivityExpired_Rejects() {
        String expiredRawToken = "inactivity_expired_token";
        String expiredHash = RefreshTokenService.hashToken(expiredRawToken);
        String family = "inactivity-family-456";

        // Expired 1 hour ago
        RefreshToken expiredToken = new RefreshToken(expiredHash, family, testUser, fixedNow.minusHours(1));
        expiredToken.setIsRevoked(false);

        when(refreshTokenRepository.findByTokenHashWithLock(expiredHash)).thenReturn(Optional.of(expiredToken));

        InvalidTokenException ex = assertThrows(InvalidTokenException.class,
                () -> refreshTokenService.rotateRefreshToken(expiredRawToken, "127.0.0.1"));

        assertTrue(ex.getMessage().contains("expired"));
        assertTrue(expiredToken.getIsRevoked());
    }

    @Test
    @DisplayName("rotateRefreshToken should reject family exceeding 30-day absolute lifetime and revoke family")
    void testRotateRefreshToken_Exceeded30DayAbsoluteLifetime_RejectsAndRevokesFamily() {
        String rawToken = "active_token_in_old_family";
        String hash = RefreshTokenService.hashToken(rawToken);
        String family = "old-family-31-days";

        RefreshToken token = new RefreshToken(hash, family, testUser, fixedNow.plusDays(2));
        token.setIsRevoked(false);

        // Family originated 31 days ago (> 30 days)
        when(refreshTokenRepository.findByTokenHashWithLock(hash)).thenReturn(Optional.of(token));
        when(refreshTokenRepository.findEarliestCreatedAtByTokenFamily(family))
                .thenReturn(Optional.of(fixedNow.minusDays(31)));

        InvalidTokenException ex = assertThrows(InvalidTokenException.class,
                () -> refreshTokenService.rotateRefreshToken(rawToken, "127.0.0.1"));

        assertTrue(ex.getMessage().contains("maximum 30-day session lifetime reached"));
        verify(refreshTokenRepository).revokeByTokenFamily(eq(family), eq(fixedNow));
    }

    @Test
    @DisplayName("rotateRefreshToken should reject inactive user and revoke token family")
    void testRotateRefreshToken_InactiveUser_RejectsAndRevokesFamily() {
        testUser.setIsActive(false);

        String rawToken = "valid_token_inactive_user";
        String hash = RefreshTokenService.hashToken(rawToken);
        String family = "inactive-user-family";

        RefreshToken token = new RefreshToken(hash, family, testUser, fixedNow.plusDays(4));
        token.setIsRevoked(false);

        when(refreshTokenRepository.findByTokenHashWithLock(hash)).thenReturn(Optional.of(token));
        when(refreshTokenRepository.findEarliestCreatedAtByTokenFamily(family))
                .thenReturn(Optional.of(fixedNow.minusDays(1)));

        InvalidTokenException ex = assertThrows(InvalidTokenException.class,
                () -> refreshTokenService.rotateRefreshToken(rawToken, "127.0.0.1"));

        assertTrue(ex.getMessage().contains("User account is disabled"));
        verify(refreshTokenRepository).revokeByTokenFamily(eq(family), eq(fixedNow));
    }

    @Test
    @DisplayName("revokeRefreshToken should revoke session family on logout and be safe to repeat")
    void testRevokeRefreshToken_SuccessAndSafeToRepeat() {
        String rawToken = "logout_token_to_revoke";
        String hash = RefreshTokenService.hashToken(rawToken);
        String family = "logout-family-777";

        RefreshToken token = new RefreshToken(hash, family, testUser, fixedNow.plusDays(4));
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(token));

        refreshTokenService.revokeRefreshToken(rawToken);

        verify(refreshTokenRepository).revokeByTokenFamily(eq(family), eq(fixedNow));

        // Safe to repeat: when token not found or null
        assertDoesNotThrow(() -> refreshTokenService.revokeRefreshToken(null));
        assertDoesNotThrow(() -> refreshTokenService.revokeRefreshToken(""));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> refreshTokenService.revokeRefreshToken("non_existent_token"));
    }
}


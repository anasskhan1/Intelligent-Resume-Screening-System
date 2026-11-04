package com.system.resume.service;

import com.system.resume.dto.RefreshTokenResult;
import com.system.resume.exception.InvalidTokenException;
import com.system.resume.model.RefreshToken;
import com.system.resume.model.User;
import com.system.resume.repository.RefreshTokenRepository;
import com.system.resume.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Service managing cryptographically secure opaque refresh tokens,
 * rotation, reuse detection, token-family lifecycles, and database row locking.
 */
@Service
public class RefreshTokenService {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenService.class);

    public static final Duration INACTIVITY_LIMIT = Duration.ofDays(7);
    public static final Duration ABSOLUTE_FAMILY_LIFETIME = Duration.ofDays(30);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final TokenFamilyRevocationService tokenFamilyRevocationService;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            TokenFamilyRevocationService tokenFamilyRevocationService
    ) {
        this(refreshTokenRepository, userRepository, tokenFamilyRevocationService, Clock.systemUTC());
    }

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            TokenFamilyRevocationService tokenFamilyRevocationService,
            Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.tokenFamilyRevocationService = tokenFamilyRevocationService != null
                ? tokenFamilyRevocationService
                : new TokenFamilyRevocationService(refreshTokenRepository);
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.secureRandom = new SecureRandom();
    }

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            Clock clock
    ) {
        this(refreshTokenRepository, userRepository, new TokenFamilyRevocationService(refreshTokenRepository), clock);
    }

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository
    ) {
        this(refreshTokenRepository, userRepository, new TokenFamilyRevocationService(refreshTokenRepository), Clock.systemUTC());
    }

    /**
     * Issues an initial refresh token for a newly authenticated user session.
     * Generates a new token family and sets a 7-day inactivity expiration.
     */
    @Transactional
    public RefreshTokenResult createRefreshToken(User user, String clientIp) {
        Objects.requireNonNull(user, "User cannot be null when issuing refresh token");

        LocalDateTime now = LocalDateTime.now(this.clock);
        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);
        String tokenFamily = UUID.randomUUID().toString();

        LocalDateTime expiresAt = now.plus(INACTIVITY_LIMIT);

        RefreshToken token = new RefreshToken(tokenHash, tokenFamily, user, expiresAt, clientIp);
        RefreshToken savedToken = refreshTokenRepository.save(token);

        logger.info("Created refresh token family '{}' for user ID: {}", tokenFamily, user.getId());
        return new RefreshTokenResult(rawToken, savedToken, user);
    }

    /**
     * Rotates an existing refresh token with database locking.
     * Enforces:
     * - 7-day inactivity limit.
     * - 30-day absolute family lifetime.
     * - Reuse detection on already-revoked tokens (revokes entire token family in an isolated REQUIRES_NEW transaction).
     * - Active user account validation.
     * - One-to-one replacement linkage.
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public RefreshTokenResult rotateRefreshToken(String rawRefreshToken, String clientIp) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        String tokenHash = hashToken(rawRefreshToken.trim());

        // Pessimistic lock row to prevent race conditions during concurrent refresh attempts
        RefreshToken token = refreshTokenRepository.findByTokenHashWithLock(tokenHash)
                .orElseThrow(() -> {
                    logger.warn("Refresh rejected: token hash not found in database");
                    return new InvalidTokenException("Invalid refresh token");
                });

        LocalDateTime now = LocalDateTime.now(this.clock);

        // 1. Reuse detection: if token was already revoked, an attacker or replay occurred.
        // Revoke the entire family in a separate REQUIRES_NEW transaction so it survives the 401 error response.
        if (Boolean.TRUE.equals(token.getIsRevoked())) {
            logger.warn("SECURITY ALERT: Reuse of revoked refresh token detected in family '{}'. Revoking entire family.",
                    token.getTokenFamily());
            tokenFamilyRevocationService.revokeEntireFamily(token.getTokenFamily(), now);
            throw new InvalidTokenException("Compromised token: reuse of revoked token detected");
        }

        // 2. 7-day inactivity limit
        if (token.getExpiresAt() != null && now.isAfter(token.getExpiresAt())) {
            logger.warn("Refresh rejected: token in family '{}' has expired due to 7-day inactivity", token.getTokenFamily());
            token.setIsRevoked(true);
            token.setRevokedAt(now);
            refreshTokenRepository.save(token);
            throw new InvalidTokenException("Refresh token has expired");
        }

        // 3. 30-day absolute family lifetime
        LocalDateTime familyCreatedAt = refreshTokenRepository.findEarliestCreatedAtByTokenFamily(token.getTokenFamily())
                .orElse(token.getCreatedAt() != null ? token.getCreatedAt() : now);

        LocalDateTime absoluteMaxExpiry = familyCreatedAt.plus(ABSOLUTE_FAMILY_LIFETIME);
        if (now.isAfter(absoluteMaxExpiry)) {
            logger.warn("Refresh rejected: token family '{}' exceeded 30-day absolute lifetime", token.getTokenFamily());
            tokenFamilyRevocationService.revokeEntireFamily(token.getTokenFamily(), now);
            throw new InvalidTokenException("Session expired: maximum 30-day session lifetime reached");
        }

        // 4. Verify user exists and is active
        User user = token.getUser();
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            logger.warn("Refresh rejected: user associated with family '{}' is inactive or disabled", token.getTokenFamily());
            tokenFamilyRevocationService.revokeEntireFamily(token.getTokenFamily(), now);
            throw new InvalidTokenException("User account is disabled or does not exist");
        }

        // 5. Rotate: revoke old token and record replacement
        token.setIsRevoked(true);
        token.setRevokedAt(now);

        // Calculate new expiration (7 days from now, but capped at 30-day family max)
        LocalDateTime nextExpiry = now.plus(INACTIVITY_LIMIT);
        if (nextExpiry.isAfter(absoluteMaxExpiry)) {
            nextExpiry = absoluteMaxExpiry;
        }

        String newRawToken = generateRawToken();
        String newTokenHash = hashToken(newRawToken);

        RefreshToken replacement = new RefreshToken(
                newTokenHash,
                token.getTokenFamily(),
                user,
                nextExpiry,
                clientIp
        );
        RefreshToken savedReplacement = refreshTokenRepository.save(replacement);

        token.setReplacedBy(savedReplacement);
        refreshTokenRepository.save(token);

        logger.info("Successfully rotated refresh token for user ID: {} in family '{}'", user.getId(), token.getTokenFamily());
        return new RefreshTokenResult(newRawToken, savedReplacement, user);
    }

    /**
     * Revokes a refresh token session by invalidating the entire token family.
     * Safe to repeat if token is missing or already revoked.
     */
    @Transactional
    public void revokeRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        try {
            String tokenHash = hashToken(rawRefreshToken.trim());
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
                LocalDateTime now = LocalDateTime.now(this.clock);
                tokenFamilyRevocationService.revokeEntireFamily(token.getTokenFamily(), now);
                logger.info("Revoked session for token family '{}'", token.getTokenFamily());
            });
        } catch (Exception ex) {
            logger.warn("Error during token revocation: {}", ex.getMessage());
        }
    }

    /**
     * Generates a 32-byte cryptographically secure random token, Base64URL-encoded without padding.
     */
    private String generateRawToken() {
        byte[] randomBytes = new byte[32];
        this.secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Computes the SHA-256 hash formatted as a 64-character lowercase hex string.
     */
    public static String hashToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Token cannot be null or blank for hashing");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available in JVM", e);
        }
    }
}


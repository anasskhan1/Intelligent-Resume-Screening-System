package com.system.resume.security;

import com.system.resume.model.Role;
import com.system.resume.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dedicated JWT service managing HMAC-SHA256 (HS256) signed access tokens
 * using JJWT 0.12.5.
 *
 * Enforces:
 * - Exactly 15-minute token lifetime.
 * - Minimum 256-bit (32 bytes) secret validation with no hardcoded fallback.
 * - Standard claims: subject (email), issuer, issued-at, expiration.
 * - Custom claims: userId, fullName, roles.
 */
@Service
public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);

    public static final Duration ACCESS_TOKEN_VALIDITY = Duration.ofMinutes(15);
    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_FULL_NAME = "fullName";
    public static final String CLAIM_ROLES = "roles";

    private final SecretKey signingKey;
    private final String issuer;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtService(
            @Value("${JWT_SECRET:${jwt.secret:}}") String jwtSecret,
            @Value("${JWT_ISSUER:${jwt.issuer:intelligent-resume-screening-system}}") String issuer
    ) {
        this(jwtSecret, issuer, Clock.systemUTC());
    }

    public JwtService(String jwtSecret, String issuer, Clock clock) {
        this.signingKey = validateAndInitializeSecret(jwtSecret);
        this.issuer = (issuer != null && !issuer.isBlank()) ? issuer.trim() : "intelligent-resume-screening-system";
        this.clock = (clock != null) ? clock : Clock.systemUTC();
    }

    /**
     * Validates that the provided secret is non-empty and contains at least 256 bits (32 bytes).
     * Rejects startup safely without any production fallback.
     */
    private SecretKey validateAndInitializeSecret(String jwtSecret) {
        if (jwtSecret == null || jwtSecret.isBlank() || "${JWT_SECRET}".equals(jwtSecret.trim())) {
            throw new IllegalStateException(
                    "Application startup failed: Required configuration 'JWT_SECRET' is missing. " +
                    "A cryptographically secure secret of at least 256 bits (32 bytes) is required."
            );
        }
        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "Application startup failed: 'JWT_SECRET' is insufficiently strong. " +
                    "Minimum 256 bits (32 bytes) required, but received " + (secretBytes.length * 8) + " bits (" + secretBytes.length + " bytes)."
            );
        }
        return Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * Generates a 15-minute access token for the given user using current clock time.
     */
    public String generateAccessToken(User user) {
        return generateAccessToken(user, Instant.now(this.clock));
    }

    /**
     * Generates a 15-minute access token for the given user using a specific instant.
     */
    public String generateAccessToken(User user, Instant now) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null when generating access token");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalArgumentException("User email cannot be null or blank when generating access token");
        }

        Instant expirationInstant = now.plus(ACCESS_TOKEN_VALIDITY);
        Date issuedAt = Date.from(now);
        Date expiration = Date.from(expirationInstant);

        List<String> roles = (user.getRoles() == null) ? List.of() :
                user.getRoles().stream()
                        .map(Role::getName)
                        .filter(Objects::nonNull)
                        .sorted()
                        .collect(Collectors.toList());

        return Jwts.builder()
                .header()
                    .type("JWT")
                    .and()
                .issuer(this.issuer)
                .subject(user.getEmail())
                .issuedAt(issuedAt)
                .expiration(expiration)
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_FULL_NAME, user.getFullName() != null ? user.getFullName().trim() : "")
                .claim(CLAIM_ROLES, roles)
                .signWith(this.signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parses and cryptographically validates the token signature, issuer, and expiration.
     * Returns the payload claims if valid.
     * Throws JwtException or IllegalArgumentException if invalid, expired, malformed, or tampered.
     */
    public Claims extractAllClaims(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("JWT token must not be null or blank");
        }
        return Jwts.parser()
                .verifyWith(this.signingKey)
                .requireIssuer(this.issuer)
                .clock(() -> Date.from(Instant.now(this.clock)))
                .build()
                .parseSignedClaims(token.trim())
                .getPayload();
    }

    public String extractSubject(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractEmail(String token) {
        return extractSubject(token);
    }

    public Long extractUserId(String token) {
        Claims claims = extractAllClaims(token);
        Object userIdObj = claims.get(CLAIM_USER_ID);
        if (userIdObj instanceof Number) {
            return ((Number) userIdObj).longValue();
        }
        return null;
    }

    public String extractFullName(String token) {
        return extractAllClaims(token).get(CLAIM_FULL_NAME, String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Claims claims = extractAllClaims(token);
        Object rolesObj = claims.get(CLAIM_ROLES);
        if (rolesObj instanceof List<?>) {
            return (List<String>) rolesObj;
        }
        return List.of();
    }

    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }

    public Date extractIssuedAt(String token) {
        return extractAllClaims(token).getIssuedAt();
    }

    public String getIssuer() {
        return issuer;
    }

    public boolean validateToken(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }
}


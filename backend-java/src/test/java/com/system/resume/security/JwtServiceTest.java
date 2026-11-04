package com.system.resume.security;

import com.system.resume.model.Role;
import com.system.resume.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    // 256-bit (32 bytes) secure test key
    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEST_ISSUER = "intelligent-resume-screening-system";

    private JwtService jwtService;
    private User testUser;
    private Instant fixedInstant;

    @BeforeEach
    void setUp() {
        fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        jwtService = new JwtService(TEST_SECRET, TEST_ISSUER, fixedClock);

        Role roleRecruiter = new Role(1L, "ROLE_RECRUITER", "Recruiter role", null);
        testUser = new User("sarah.connor@example.com", "hash", "Sarah", "Connor", true);
        testUser.setId(42L);
        testUser.setRoles(Set.of(roleRecruiter));
    }

    @Test
    @DisplayName("Should successfully generate access token with all agreed claims")
    void testGenerateAccessToken_ContainsAllAgreedClaims() {
        String token = jwtService.generateAccessToken(testUser);

        assertNotNull(token);
        assertFalse(token.isBlank());

        Claims claims = jwtService.extractAllClaims(token);

        assertEquals("sarah.connor@example.com", claims.getSubject());
        assertEquals(TEST_ISSUER, claims.getIssuer());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());

        assertEquals(42L, jwtService.extractUserId(token));
        assertEquals("Sarah Connor", jwtService.extractFullName(token));
        assertEquals(List.of("ROLE_RECRUITER"), jwtService.extractRoles(token));
    }

    @Test
    @DisplayName("Access token lifetime must be exactly 15 minutes (900 seconds)")
    void testAccessTokenLifetime_IsExactly15Minutes() {
        String token = jwtService.generateAccessToken(testUser);
        Claims claims = jwtService.extractAllClaims(token);

        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();

        long diffSeconds = (expiration.getTime() - issuedAt.getTime()) / 1000;
        assertEquals(15 * 60, diffSeconds, "Token validity must be exactly 900 seconds (15 minutes)");
        assertEquals(Duration.ofMinutes(15).toSeconds(), diffSeconds);
    }

    @Test
    @DisplayName("Expired token must be rejected with ExpiredJwtException")
    void testValidateToken_ExpiredToken_ThrowsExpiredJwtException() {
        // Generate token at fixedInstant (10:00)
        String token = jwtService.generateAccessToken(testUser, fixedInstant);

        // Advance clock by 15 minutes and 1 second (10:15:01)
        Instant pastExpiration = fixedInstant.plus(Duration.ofMinutes(15)).plusSeconds(1);
        Clock advancedClock = Clock.fixed(pastExpiration, ZoneOffset.UTC);
        JwtService verifierWithAdvancedClock = new JwtService(TEST_SECRET, TEST_ISSUER, advancedClock);

        assertThrows(ExpiredJwtException.class, () -> verifierWithAdvancedClock.extractAllClaims(token));
        assertFalse(verifierWithAdvancedClock.validateToken(token));
    }

    @Test
    @DisplayName("Token signed with different secret must be rejected with SignatureException")
    void testValidateToken_DifferentSecret_ThrowsSignatureException() {
        String otherSecret = "different_secret_key_that_is_at_least_32_bytes_long_123456";
        JwtService otherJwtService = new JwtService(otherSecret, TEST_ISSUER, Clock.fixed(fixedInstant, ZoneOffset.UTC));

        String token = otherJwtService.generateAccessToken(testUser);

        assertThrows(SignatureException.class, () -> jwtService.extractAllClaims(token));
        assertFalse(jwtService.validateToken(token));
    }

    @Test
    @DisplayName("Tampered token payload must be rejected with SignatureException")
    void testValidateToken_TamperedPayload_ThrowsSignatureException() {
        String token = jwtService.generateAccessToken(testUser);
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);

        // Tamper with payload characters
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 2) + "==";
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThrows(SignatureException.class, () -> jwtService.extractAllClaims(tamperedToken));
        assertFalse(jwtService.validateToken(tamperedToken));
    }

    @Test
    @DisplayName("Malformed token must be rejected with MalformedJwtException")
    void testValidateToken_MalformedToken_ThrowsMalformedJwtException() {
        String malformedToken = "invalid.token.structure";
        assertThrows(MalformedJwtException.class, () -> jwtService.extractAllClaims(malformedToken));
        assertFalse(jwtService.validateToken(malformedToken));
    }

    @Test
    @DisplayName("Missing or blank JWT_SECRET must throw IllegalStateException during initialization")
    void testMissingSecret_ThrowsIllegalStateException() {
        IllegalStateException exNull = assertThrows(IllegalStateException.class, () -> new JwtService(null, TEST_ISSUER));
        assertTrue(exNull.getMessage().contains("Required configuration 'JWT_SECRET' is missing"));

        IllegalStateException exEmpty = assertThrows(IllegalStateException.class, () -> new JwtService("", TEST_ISSUER));
        assertTrue(exEmpty.getMessage().contains("Required configuration 'JWT_SECRET' is missing"));

        IllegalStateException exBlank = assertThrows(IllegalStateException.class, () -> new JwtService("   ", TEST_ISSUER));
        assertTrue(exBlank.getMessage().contains("Required configuration 'JWT_SECRET' is missing"));

        IllegalStateException exPlaceholder = assertThrows(IllegalStateException.class, () -> new JwtService("${JWT_SECRET}", TEST_ISSUER));
        assertTrue(exPlaceholder.getMessage().contains("Required configuration 'JWT_SECRET' is missing"));
    }

    @Test
    @DisplayName("Weak JWT_SECRET (< 256 bits / 32 bytes) must throw IllegalStateException during initialization")
    void testWeakSecret_ThrowsIllegalStateException() {
        String shortSecret16Bytes = "1234567890123456"; // 16 bytes = 128 bits
        assertEquals(16, shortSecret16Bytes.getBytes(StandardCharsets.UTF_8).length);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new JwtService(shortSecret16Bytes, TEST_ISSUER));
        assertTrue(ex.getMessage().contains("is insufficiently strong"));
        assertTrue(ex.getMessage().contains("128 bits"));
    }

    @Test
    @DisplayName("Sufficiently strong JWT_SECRET (>= 256 bits) must initialize without exception")
    void testSufficientSecret_InitializesSuccessfully() {
        String validSecret32Bytes = "12345678901234567890123456789012"; // exactly 32 bytes = 256 bits
        assertEquals(32, validSecret32Bytes.getBytes(StandardCharsets.UTF_8).length);

        assertDoesNotThrow(() -> new JwtService(validSecret32Bytes, TEST_ISSUER));
    }
}


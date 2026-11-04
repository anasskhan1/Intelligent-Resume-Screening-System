package com.system.resume.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    @DisplayName("PasswordEncoder should be BCryptPasswordEncoder with configured work factor 12")
    void testPasswordEncoder_IsConfiguredWithStrength12() {
        PasswordEncoder encoder = securityConfig.passwordEncoder();
        assertNotNull(encoder);
        assertInstanceOf(BCryptPasswordEncoder.class, encoder);

        String rawPassword = "StrongP@ssw0rd123";
        String encoded = encoder.encode(rawPassword);

        assertNotNull(encoded);
        assertNotEquals(rawPassword, encoded);

        // Verify the work factor in the encoded hash is explicitly 12 ($2a$12$ or $2b$12$)
        assertTrue(encoded.startsWith("$2a$12$") || encoded.startsWith("$2b$12$"),
                "Hash must start with $2a$12$ or $2b$12$, indicating work factor 12: " + encoded);

        assertTrue(encoder.matches(rawPassword, encoded));
        assertFalse(encoder.matches("WrongPassword", encoded));
    }

    @Test
    @DisplayName("PasswordEncoder must preserve exact leading and trailing spaces without trimming")
    void testPasswordEncoder_PreservesLeadingAndTrailingSpaces() {
        PasswordEncoder encoder = securityConfig.passwordEncoder();

        String spacedPassword = "  P@ssw0rdWithSpaces  ";
        String encoded = encoder.encode(spacedPassword);

        // Matching with exact spaces must succeed
        assertTrue(encoder.matches(spacedPassword, encoded));

        // Matching with trimmed password must fail
        assertFalse(encoder.matches("P@ssw0rdWithSpaces", encoded));
        assertFalse(encoder.matches("  P@ssw0rdWithSpaces", encoded));
        assertFalse(encoder.matches("P@ssw0rdWithSpaces  ", encoded));
    }
}


package com.system.resume.security;

import com.system.resume.exception.CsrfValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class CsrfProtectionServiceTest {

    private CsrfProtectionService csrfProtectionService;

    @BeforeEach
    void setUp() {
        csrfProtectionService = new CsrfProtectionService("http://localhost:5500,http://127.0.0.1:5500");
    }

    @Test
    @DisplayName("Should pass CSRF validation when X-Requested-With and allowed Origin are present")
    void testValidateCsrf_ValidHeaderAndOrigin_Success() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        request.addHeader("Origin", "http://localhost:5500");

        assertDoesNotThrow(() -> csrfProtectionService.validateCsrf(request));
    }

    @Test
    @DisplayName("Should pass CSRF validation when X-Requested-With and allowed Referer are present")
    void testValidateCsrf_ValidHeaderAndReferer_Success() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.addHeader("X-Requested-With", "fetch");
        request.addHeader("Referer", "http://127.0.0.1:5500/dashboard.html");

        assertDoesNotThrow(() -> csrfProtectionService.validateCsrf(request));
    }

    @Test
    @DisplayName("Should reject request with CsrfValidationException when X-Requested-With header is missing")
    void testValidateCsrf_MissingCustomHeader_ThrowsException() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.addHeader("Origin", "http://localhost:5500");

        CsrfValidationException ex = assertThrows(CsrfValidationException.class,
                () -> csrfProtectionService.validateCsrf(request));
        assertTrue(ex.getMessage().contains("Missing required 'X-Requested-With' header"));
    }

    @Test
    @DisplayName("Should reject request with CsrfValidationException when Origin is not in allowed origins")
    void testValidateCsrf_UntrustedOrigin_ThrowsException() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        request.addHeader("Origin", "http://evil-attacker.com");

        CsrfValidationException ex = assertThrows(CsrfValidationException.class,
                () -> csrfProtectionService.validateCsrf(request));
        assertTrue(ex.getMessage().contains("is not permitted"));
    }

    @Test
    @DisplayName("Should reject request with CsrfValidationException when both Origin and Referer are absent")
    void testValidateCsrf_MissingBothOriginAndReferer_ThrowsException() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.addHeader("X-Requested-With", "XMLHttpRequest");

        CsrfValidationException ex = assertThrows(CsrfValidationException.class,
                () -> csrfProtectionService.validateCsrf(request));
        assertTrue(ex.getMessage().contains("Origin or Referer header is required"));
    }
}


package com.system.resume.security;

import com.system.resume.model.Role;
import com.system.resume.model.User;
import com.system.resume.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEST_ISSUER = "intelligent-resume-screening-system";

    @Mock
    private UserRepository userRepository;

    @Mock
    private FilterChain filterChain;

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;
    private User testUser;
    private Instant fixedInstant;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        jwtService = new JwtService(TEST_SECRET, TEST_ISSUER, fixedClock);
        filter = new JwtAuthenticationFilter(jwtService, userRepository);

        Role roleRecruiter = new Role(1L, "ROLE_RECRUITER", "Recruiter role", null);
        testUser = new User("recruiter@example.com", "hashed_password", "Jane", "Doe", true);
        testUser.setId(10L);
        testUser.setRoles(Set.of(roleRecruiter));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Valid Bearer token authenticates successfully and populates SecurityContext")
    void testDoFilter_ValidBearerToken_AuthenticatesAndPopulatesSecurityContext() throws ServletException, IOException {
        String token = jwtService.generateAccessToken(testUser);
        when(userRepository.findByEmail("recruiter@example.com")).thenReturn(Optional.of(testUser));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/resumes");
        request.setServletPath("/api/v1/resumes");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("recruiter@example.com", SecurityContextHolder.getContext().getAuthentication().getName());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_RECRUITER")));
    }

    @Test
    @DisplayName("Expired Bearer token is rejected with 401 Unauthorized and clears SecurityContext")
    void testDoFilter_ExpiredToken_RejectsWith401AndClearsContext() throws ServletException, IOException {
        // Token issued at 10:00
        String token = jwtService.generateAccessToken(testUser, fixedInstant);

        // Filter clock initialized 20 minutes later (10:20)
        Instant futureInstant = fixedInstant.plus(Duration.ofMinutes(20));
        JwtService expiredVerifier = new JwtService(TEST_SECRET, TEST_ISSUER, Clock.fixed(futureInstant, ZoneOffset.UTC));
        JwtAuthenticationFilter expiredFilter = new JwtAuthenticationFilter(expiredVerifier, userRepository);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/resumes");
        request.setServletPath("/api/v1/resumes");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        expiredFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Token has expired"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Tampered token is rejected with 401 Unauthorized and clears SecurityContext")
    void testDoFilter_TamperedToken_RejectsWith401() throws ServletException, IOException {
        String validToken = jwtService.generateAccessToken(testUser);
        String[] parts = validToken.split("\\.");
        String tamperedToken = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "==" + "." + parts[2];

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.setServletPath("/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + tamperedToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Invalid token signature") ||
                   response.getContentAsString().contains("Unauthorized"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Malformed token is rejected with 401 Unauthorized")
    void testDoFilter_MalformedToken_RejectsWith401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.setServletPath("/api/v1/candidates");
        request.addHeader("Authorization", "Bearer not.a.valid.jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Malformed token") ||
                   response.getContentAsString().contains("Unauthorized"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Missing Bearer token allows request to proceed down filter chain without authentication")
    void testDoFilter_MissingBearerToken_PassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.setServletPath("/api/v1/candidates");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Non-Bearer Authorization header allows request to proceed down filter chain without authentication")
    void testDoFilter_NonBearerHeader_PassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.setServletPath("/api/v1/candidates");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Nonexistent user in database cannot authenticate with a token")
    void testDoFilter_NonexistentUser_RejectsWith401() throws ServletException, IOException {
        String token = jwtService.generateAccessToken(testUser);
        when(userRepository.findByEmail("recruiter@example.com")).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.setServletPath("/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("User account does not exist"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Inactive/deactivated user cannot authenticate with a token")
    void testDoFilter_InactiveUser_RejectsWith401() throws ServletException, IOException {
        testUser.setIsActive(false);
        String token = jwtService.generateAccessToken(testUser);
        when(userRepository.findByEmail("recruiter@example.com")).thenReturn(Optional.of(testUser));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.setServletPath("/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("User account is disabled"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("shouldNotFilter must return true for public routes (register, login, health)")
    void testShouldNotFilter_PublicEndpoints() {
        MockHttpServletRequest registerReq = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        registerReq.setServletPath("/api/v1/auth/register");
        assertTrue(filter.shouldNotFilter(registerReq));

        MockHttpServletRequest loginReq = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        loginReq.setServletPath("/api/v1/auth/login");
        assertTrue(filter.shouldNotFilter(loginReq));

        MockHttpServletRequest healthReq = new MockHttpServletRequest("GET", "/health");
        healthReq.setServletPath("/health");
        assertTrue(filter.shouldNotFilter(healthReq));

        MockHttpServletRequest candidatesReq = new MockHttpServletRequest("GET", "/api/v1/candidates");
        candidatesReq.setServletPath("/api/v1/candidates");
        assertFalse(filter.shouldNotFilter(candidatesReq));

        MockHttpServletRequest otherAuthReq = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        otherAuthReq.setServletPath("/api/v1/auth/me");
        assertFalse(filter.shouldNotFilter(otherAuthReq));
    }
}


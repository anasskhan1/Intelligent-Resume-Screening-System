package com.system.resume.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.resume.dto.AuthResponseDto;
import com.system.resume.dto.LoginRequestDto;
import com.system.resume.dto.LoginResult;
import com.system.resume.dto.RefreshTokenResult;
import com.system.resume.dto.RegisterRequestDto;
import com.system.resume.dto.UserResponseDto;
import com.system.resume.exception.EmailAlreadyExistsException;
import com.system.resume.exception.GlobalExceptionHandler;
import com.system.resume.exception.InvalidCredentialsException;
import com.system.resume.exception.InvalidTokenException;
import com.system.resume.model.Role;
import com.system.resume.model.User;
import com.system.resume.security.AuthCookieService;
import com.system.resume.security.CsrfProtectionService;
import com.system.resume.security.JwtService;
import com.system.resume.service.AuthService;
import com.system.resume.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtService jwtService;

    private AuthCookieService authCookieService;
    private CsrfProtectionService csrfProtectionService;
    private AuthController authController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        authCookieService = new AuthCookieService(false, "Lax");
        csrfProtectionService = new CsrfProtectionService("http://localhost:5500,http://127.0.0.1:5500");
        authController = new AuthController(
                authService,
                refreshTokenService,
                jwtService,
                authCookieService,
                csrfProtectionService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - Should return 201 Created and safe UserResponseDto")
    void testRegister_Success_Returns201Created() throws Exception {
        RegisterRequestDto request = new RegisterRequestDto(
                "recruiter@example.com",
                "StrongPassword123!",
                "John",
                "Doe"
        );

        UserResponseDto response = new UserResponseDto(
                1L,
                "recruiter@example.com",
                "John",
                "Doe",
                "John Doe",
                true,
                Set.of("ROLE_RECRUITER"),
                LocalDateTime.now()
        );

        when(authService.register(any(RegisterRequestDto.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("recruiter@example.com"))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Doe"))
                .andExpect(jsonPath("$.fullName").value("John Doe"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_RECRUITER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - Should reject duplicate email with 409 Conflict")
    void testRegister_DuplicateEmail_Returns409Conflict() throws Exception {
        RegisterRequestDto request = new RegisterRequestDto(
                "existing@example.com",
                "Password123!",
                "Jane",
                "Doe"
        );

        when(authService.register(any(RegisterRequestDto.class)))
                .thenThrow(new EmailAlreadyExistsException("Email 'existing@example.com' is already registered."));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Email 'existing@example.com' is already registered."));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - Should reject invalid email with 400 Bad Request")
    void testRegister_InvalidEmail_Returns400BadRequest() throws Exception {
        RegisterRequestDto request = new RegisterRequestDto(
                "not-an-email",
                "Password123!",
                "Jane",
                "Doe"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - Should reject short password (< 8 chars) with 400 Bad Request")
    void testRegister_ShortPassword_Returns400BadRequest() throws Exception {
        RegisterRequestDto request = new RegisterRequestDto(
                "valid@example.com",
                "short",
                "Jane",
                "Doe"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - Should reject password exceeding 72 characters with 400 Bad Request")
    void testRegister_PasswordExceeds72Chars_Returns400BadRequest() throws Exception {
        RegisterRequestDto request = new RegisterRequestDto(
                "valid@example.com",
                "A".repeat(73),
                "Jane",
                "Doe"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Should return 200 OK, access token in body, and HttpOnly refresh cookie")
    void testLogin_Success_Returns200OkAndHttpOnlyCookie() throws Exception {
        LoginRequestDto request = new LoginRequestDto("recruiter@example.com", "Password123!");

        UserResponseDto userDto = new UserResponseDto(
                1L,
                "recruiter@example.com",
                "Jane",
                "Doe",
                "Jane Doe",
                true,
                Set.of("ROLE_RECRUITER"),
                LocalDateTime.now()
        );

        AuthResponseDto authResponse = new AuthResponseDto("access.jwt.token", userDto);
        LoginResult loginResult = new LoginResult(authResponse, "raw.refresh.token.value");

        when(authService.login(any(LoginRequestDto.class), any())).thenReturn(loginResult);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("recruiter@example.com"))
                .andExpect(jsonPath("$.fullName").value("Jane Doe"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_RECRUITER"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(header().string("Set-Cookie", containsString("refreshToken=raw.refresh.token.value")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Should reject invalid password with 401 Unauthorized")
    void testLogin_InvalidCredentials_Returns401Unauthorized() throws Exception {
        LoginRequestDto request = new LoginRequestDto("recruiter@example.com", "WrongPassword");

        when(authService.login(any(LoginRequestDto.class), any()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password."));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Should reject inactive account with generic 401 Unauthorized to prevent enumeration")
    void testLogin_InactiveAccount_Returns401Unauthorized() throws Exception {
        LoginRequestDto request = new LoginRequestDto("inactive@example.com", "CorrectPassword123!");

        when(authService.login(any(LoginRequestDto.class), any()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password."));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Should rotate token, return new access token, and set new cookie")
    void testRefresh_Success_RotatesToken() throws Exception {
        User user = new User("sarah@example.com", "hash", "Sarah", "Connor", true);
        user.setId(42L);
        user.setRoles(Set.of(new Role(1L, "ROLE_RECRUITER", "Recruiter", null)));

        RefreshTokenResult refreshResult = new RefreshTokenResult("new.rotated.raw.token", null, user);
        when(refreshTokenService.rotateRefreshToken(eq("existing.raw.token"), any())).thenReturn(refreshResult);
        when(jwtService.generateAccessToken(user)).thenReturn("new.access.jwt.token");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://localhost:5500")
                        .cookie(new Cookie("refreshToken", "existing.raw.token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.jwt.token"))
                .andExpect(jsonPath("$.email").value("sarah@example.com"))
                .andExpect(header().string("Set-Cookie", containsString("refreshToken=new.rotated.raw.token")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Missing X-Requested-With header must reject with 403 Forbidden (CSRF)")
    void testRefresh_MissingCsrfHeader_Returns403Forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:5500")
                        .cookie(new Cookie("refreshToken", "some.token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(containsString("Missing required 'X-Requested-With' header")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Untrusted Origin must reject with 403 Forbidden (CSRF)")
    void testRefresh_UntrustedOrigin_Returns403Forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://malicious-site.com")
                        .cookie(new Cookie("refreshToken", "some.token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Missing refresh token cookie must return 401 Unauthorized")
    void testRefresh_MissingCookie_Returns401Unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://localhost:5500"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(containsString("Missing refresh token cookie")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Compromised/revoked token must return 401 and clear cookie")
    void testRefresh_CompromisedToken_Returns401AndClearsCookie() throws Exception {
        when(refreshTokenService.rotateRefreshToken(eq("reused.token"), any()))
                .thenThrow(new InvalidTokenException("Compromised token: reuse of revoked token detected"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://localhost:5500")
                        .cookie(new Cookie("refreshToken", "reused.token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout - Valid CSRF logout revokes session and clears cookie")
    void testLogout_Success_RevokesSessionAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://localhost:5500")
                        .cookie(new Cookie("refreshToken", "valid.token.to.revoke")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(refreshTokenService).revokeRefreshToken("valid.token.to.revoke");
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout - Missing CSRF header rejects with 403 Forbidden")
    void testLogout_MissingCsrfHeader_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Origin", "http://localhost:5500")
                        .cookie(new Cookie("refreshToken", "valid.token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(refreshTokenService, never()).revokeRefreshToken(any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout - Safe to repeat without cookie")
    void testLogout_SafeToRepeat_WithoutCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://localhost:5500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }
}

package com.system.resume.config;

import com.system.resume.controller.AuthController;
import com.system.resume.dto.AuthResponseDto;
import com.system.resume.dto.LoginRequestDto;
import com.system.resume.dto.LoginResult;
import com.system.resume.dto.RegisterRequestDto;
import com.system.resume.dto.UserResponseDto;
import com.system.resume.model.Role;
import com.system.resume.model.User;
import com.system.resume.repository.UserRepository;
import com.system.resume.security.AuthCookieService;
import com.system.resume.security.CsrfProtectionService;
import com.system.resume.security.JwtAuthenticationFilter;
import com.system.resume.security.JwtService;
import com.system.resume.service.AuthService;
import com.system.resume.service.RefreshTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtAuthenticationFilter.class, JwtService.class, AuthCookieService.class, CsrfProtectionService.class})
class SecurityEndpointAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AuthService authService;

    @MockBean
    private RefreshTokenService refreshTokenService;

    @MockBean
    private UserRepository userRepository;

    @Test
    @DisplayName("POST /api/v1/auth/register should be permitted publicly")
    void testRegisterEndpoint_IsPublic() throws Exception {
        UserResponseDto response = new UserResponseDto(
                1L, "recruiter@example.com", "Jane", "Doe", "Jane Doe", true, Set.of("ROLE_RECRUITER"), LocalDateTime.now()
        );
        when(authService.register(any(RegisterRequestDto.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"recruiter@example.com\",\"password\":\"StrongPassword123!\",\"firstName\":\"Jane\",\"lastName\":\"Doe\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("recruiter@example.com"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login should be permitted publicly")
    void testLoginEndpoint_IsPublic() throws Exception {
        UserResponseDto response = new UserResponseDto(
                1L, "recruiter@example.com", "Jane", "Doe", "Jane Doe", true, Set.of("ROLE_RECRUITER"), LocalDateTime.now()
        );
        LoginResult loginResult = new LoginResult(new AuthResponseDto("access.jwt.token", response), "raw.refresh.token");
        when(authService.login(any(LoginRequestDto.class), any())).thenReturn(loginResult);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"recruiter@example.com\",\"password\":\"StrongPassword123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt.token"))
                .andExpect(jsonPath("$.email").value("recruiter@example.com"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout is permitted through Spring Security filter without Bearer token")
    void testLogoutEndpoint_IsPermittedBySecurityFilter() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://localhost:5500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me - Protected /api/v1/auth/** routes must NOT be public (return 401 Unauthorized)")
    void testOtherAuthRoutes_RequireAuthentication_GetMe() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("GET /api/v1/resumes - Anonymous requests must be rejected with 401 Unauthorized")
    void testBusinessEndpoints_RequireAuthentication_Resumes() throws Exception {
        mockMvc.perform(get("/api/v1/resumes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("GET /api/v1/candidates - Anonymous requests must be rejected with 401 Unauthorized")
    void testBusinessEndpoints_RequireAuthentication_Candidates() throws Exception {
        mockMvc.perform(get("/api/v1/candidates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Protected endpoint with valid Bearer token passes security filter")
    void testProtectedEndpoint_WithValidToken_PassesSecurityFilter() throws Exception {
        User user = new User("valid.user@example.com", "hash", "Valid", "User", true);
        user.setId(99L);
        user.setRoles(Set.of(new Role(1L, "ROLE_RECRUITER", "Recruiter", null)));

        when(userRepository.findByEmail("valid.user@example.com")).thenReturn(Optional.of(user));

        String token = jwtService.generateAccessToken(user);

        // Making request to /api/v1/auth/me with valid Bearer token passes security filter.
        // It reaches Spring MVC which returns 404 (endpoint not defined in AuthController), proving 401 was not returned.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Protected endpoint with invalid Bearer token is rejected with 401 Unauthorized")
    void testProtectedEndpoint_WithInvalidToken_RejectsWith401() throws Exception {
        mockMvc.perform(get("/api/v1/resumes")
                        .header("Authorization", "Bearer invalid.bearer.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }
}

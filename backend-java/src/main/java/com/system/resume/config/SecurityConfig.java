package com.system.resume.config;

import com.system.resume.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 configuration establishing stateless authentication and
 * role-based access control (RBAC) foundation.
 *
 * Current Architecture (Phase 7 - Step 1):
 * - Stateless session management (no HTTP sessions created or persisted).
 * - Method-level security enabled (@EnableMethodSecurity) with @PreAuthorize.
 * - CSRF disabled for stateless REST endpoints.
 * - Public access granted exclusively to POST /api/v1/auth/register, POST /api/v1/auth/login,
 *   POST /api/v1/auth/refresh, POST /api/v1/auth/logout, and /health/**.
 * - Explicit administrative routes protected with ROLE_ADMIN (/api/v1/admin/**).
 * - All other application endpoints require authentication; role boundaries enforced by method security.
 * - JwtAuthenticationFilter intercepts requests to validate Bearer JWT access tokens and populate SecurityContext.
 * - Unauthenticated requests receive HTTP 401 Unauthorized JSON error response.
 * - Unauthorized role requests receive HTTP 403 Forbidden JSON error response.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Agreed architectural BCrypt work factor (strength 12).
     */
    public static final int BCRYPT_STRENGTH = 12;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig() {
        this.jwtAuthenticationFilter = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Standard BCrypt password encoder bean configured with strength 12,
     * used consistently across registration hashing and credential login verification.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    /**
     * Exposes Spring Security AuthenticationManager bean.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Configures the HTTP security filter chain.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF for stateless REST APIs
                .csrf(AbstractHttpConfigurer::disable)

                // Enable CORS integration with CorsConfig
                .cors(Customizer.withDefaults())

                // Stateless session management: no JSESSIONID cookie
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Endpoint authorization rules: ONLY permit specific POST register, login, refresh, and logout endpoints
                .authorizeHttpRequests(auth -> auth
                        // Public authentication endpoints strictly limited to POST register, login, refresh, and logout
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout"
                        ).permitAll()

                        // Health and liveness endpoints
                        .requestMatchers("/health", "/health/**").permitAll()

                        // Explicitly protected administrative endpoints
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        // All other APIs require authentication; method security enforces role permissions
                        .anyRequest().authenticated()
                )

                // Return structured JSON errors for authentication and authorization failures
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication required to access this resource\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access denied: insufficient permissions to access this resource\"}");
                        })
                )

                // Disable default form login and basic auth prompts
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        // Intercept and authenticate JWT Bearer tokens before standard username/password filter
        if (jwtAuthenticationFilter != null) {
            http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}


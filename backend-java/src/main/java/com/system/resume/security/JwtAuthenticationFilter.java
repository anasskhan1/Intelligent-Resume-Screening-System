package com.system.resume.security;

import com.system.resume.model.User;
import com.system.resume.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * OncePerRequestFilter that intercepts incoming HTTP requests to validate JWT access tokens
 * and authenticate valid active users in Spring Security's SecurityContext.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();

        if (HttpMethod.POST.matches(method)) {
            if ("/api/v1/auth/register".equals(path)
                    || "/api/v1/auth/login".equals(path)
                    || "/api/v1/auth/refresh".equals(path)
                    || "/api/v1/auth/logout".equals(path)) {
                return true;
            }
        }
        return "/health".equals(path) || path.startsWith("/health/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // If no Authorization header or not Bearer, proceed down filter chain.
        // Protected endpoints will be caught by Spring Security's AuthorizationFilter.
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (jwt.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String email;
        try {
            email = jwtService.extractSubject(jwt);
        } catch (ExpiredJwtException ex) {
            logger.warn("JWT authentication rejected: token is expired");
            SecurityContextHolder.clearContext();
            sendUnauthorizedError(response, "Token has expired");
            return;
        } catch (SignatureException ex) {
            logger.warn("JWT authentication rejected: invalid signature or tampered token");
            SecurityContextHolder.clearContext();
            sendUnauthorizedError(response, "Invalid token signature");
            return;
        } catch (MalformedJwtException ex) {
            logger.warn("JWT authentication rejected: malformed token structure");
            SecurityContextHolder.clearContext();
            sendUnauthorizedError(response, "Malformed token");
            return;
        } catch (JwtException | IllegalArgumentException ex) {
            logger.warn("JWT authentication rejected: invalid token ({})", ex.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
            sendUnauthorizedError(response, "Invalid or unauthorized token");
            return;
        }

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<User> userOptional = userRepository.findByEmail(email);

            if (userOptional.isEmpty()) {
                logger.warn("JWT authentication rejected: user subject not found in database");
                SecurityContextHolder.clearContext();
                sendUnauthorizedError(response, "User account does not exist");
                return;
            }

            User user = userOptional.get();
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                logger.warn("JWT authentication rejected: user ID {} is deactivated", user.getId());
                SecurityContextHolder.clearContext();
                sendUnauthorizedError(response, "User account is disabled");
                return;
            }

            CustomUserDetails userDetails = new CustomUserDetails(user);
            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorizedError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(String.format(
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\"}",
                message
        ));
    }
}


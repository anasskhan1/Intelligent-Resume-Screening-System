package com.system.resume.controller;

import com.system.resume.dto.AuthResponseDto;
import com.system.resume.dto.LoginRequestDto;
import com.system.resume.dto.LoginResult;
import com.system.resume.dto.RefreshTokenResult;
import com.system.resume.dto.RegisterRequestDto;
import com.system.resume.dto.UserResponseDto;
import com.system.resume.exception.InvalidTokenException;
import com.system.resume.security.AuthCookieService;
import com.system.resume.security.CsrfProtectionService;
import com.system.resume.security.JwtService;
import com.system.resume.service.AuthService;
import com.system.resume.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * REST Controller managing public authentication, session refresh, and logout endpoints.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final AuthCookieService authCookieService;
    private final CsrfProtectionService csrfProtectionService;

    public AuthController(
            AuthService authService,
            RefreshTokenService refreshTokenService,
            JwtService jwtService,
            AuthCookieService authCookieService,
            CsrfProtectionService csrfProtectionService
    ) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.authCookieService = authCookieService;
        this.csrfProtectionService = csrfProtectionService;
    }

    /**
     * Registers a new user account with ROLE_RECRUITER and active status.
     *
     * @param request the registration details
     * @return 201 Created with safe UserResponseDto
     */
    @PostMapping("/register")
    public ResponseEntity<UserResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        UserResponseDto response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticates user credentials, issues a 15-minute access token, and delivers
     * an opaque refresh token in an HttpOnly cookie.
     *
     * @param request the login credentials
     * @param httpRequest the HTTP servlet request
     * @param httpResponse the HTTP servlet response
     * @return 200 OK with AuthResponseDto containing access token and safe user details
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(
            @Valid @RequestBody LoginRequestDto request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String clientIp = extractClientIp(httpRequest);
        LoginResult loginResult = authService.login(request, clientIp);

        if (loginResult.rawRefreshToken() != null) {
            ResponseCookie cookie = authCookieService.createRefreshTokenCookie(loginResult.rawRefreshToken());
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        return ResponseEntity.ok(loginResult.authResponse());
    }

    /**
     * Rotates the refresh token and issues a new 15-minute access token.
     * Enforces CSRF protections (X-Requested-With header + Origin/Referer allowlist).
     *
     * @param httpRequest the HTTP servlet request
     * @param httpResponse the HTTP servlet response
     * @return 200 OK with AuthResponseDto containing the new access token
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponseDto> refresh(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        csrfProtectionService.validateCsrf(httpRequest);

        String rawRefreshToken = authCookieService.extractRefreshToken(httpRequest)
                .orElseThrow(() -> new InvalidTokenException("Missing refresh token cookie"));

        try {
            String clientIp = extractClientIp(httpRequest);
            RefreshTokenResult result = refreshTokenService.rotateRefreshToken(rawRefreshToken, clientIp);

            String newAccessToken = jwtService.generateAccessToken(result.user());
            ResponseCookie newCookie = authCookieService.createRefreshTokenCookie(result.rawRefreshToken());
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, newCookie.toString());

            AuthResponseDto authResponse = new AuthResponseDto(
                    newAccessToken,
                    UserResponseDto.fromEntity(result.user())
            );
            return ResponseEntity.ok(authResponse);
        } catch (InvalidTokenException ex) {
            ResponseCookie clearCookie = authCookieService.createClearRefreshTokenCookie();
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());
            throw ex;
        }
    }

    /**
     * Revokes the refresh token session and clears the HttpOnly cookie.
     * Safe to repeat if no session is active.
     *
     * @param httpRequest the HTTP servlet request
     * @param httpResponse the HTTP servlet response
     * @return 200 OK with confirmation message
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        csrfProtectionService.validateCsrf(httpRequest);

        Optional<String> tokenOpt = authCookieService.extractRefreshToken(httpRequest);
        tokenOpt.ifPresent(refreshTokenService::revokeRefreshToken);

        ResponseCookie clearCookie = authCookieService.createClearRefreshTokenCookie();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    private String extractClientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xForwarded = request.getHeader("X-Forwarded-For");
        if (xForwarded != null && !xForwarded.isBlank()) {
            String ip = xForwarded.split(",")[0].trim();
            return ip.length() > 45 ? ip.substring(0, 45) : ip;
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && remoteAddr.length() > 45) ? remoteAddr.substring(0, 45) : remoteAddr;
    }
}

package com.system.resume.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Service managing secure HttpOnly cookie attributes for refresh tokens.
 * Enforces Path=/api/v1/auth, HttpOnly=true, SameSite=Lax (dev) / Strict (prod).
 */
@Service
public class AuthCookieService {

    public static final String REFRESH_COOKIE_NAME = "refreshToken";
    public static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    public static final Duration MAX_AGE = Duration.ofDays(30);

    private final boolean secure;
    private final String sameSite;

    public AuthCookieService(
            @Value("${cookie.refresh-token.secure:false}") boolean secure,
            @Value("${cookie.refresh-token.same-site:Lax}") String sameSite
    ) {
        this.secure = secure;
        this.sameSite = sameSite;
    }

    /**
     * Creates a new HttpOnly cookie containing the raw refresh token.
     */
    public ResponseCookie createRefreshTokenCookie(String rawRefreshToken) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, rawRefreshToken)
                .httpOnly(true)
                .secure(this.secure)
                .path(REFRESH_COOKIE_PATH)
                .maxAge(MAX_AGE)
                .sameSite(this.sameSite)
                .build();
    }

    /**
     * Creates a cookie that immediately clears the refresh token on the client.
     */
    public ResponseCookie createClearRefreshTokenCookie() {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(this.secure)
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .sameSite(this.sameSite)
                .build();
    }

    /**
     * Extracts the raw refresh token from the request cookies, supporting both 'refreshToken'
     * and 'refresh_token' naming.
     */
    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_COOKIE_NAME.equals(c.getName()) || "refresh_token".equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    public boolean isSecure() {
        return secure;
    }

    public String getSameSite() {
        return sameSite;
    }
}


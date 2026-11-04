package com.system.resume.security;

import com.system.resume.exception.CsrfValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates CSRF countermeasures for state-changing authentication endpoints (refresh and logout).
 * Enforces the presence of custom X-Requested-With header and validates Origin/Referer against allowlist.
 */
@Service
public class CsrfProtectionService {

    private static final Logger logger = LoggerFactory.getLogger(CsrfProtectionService.class);
    public static final String REQUIRED_HEADER = "X-Requested-With";

    private final Set<String> allowedOrigins;

    public CsrfProtectionService(
            @Value("${cors.allowed-origins:http://localhost:5500,http://127.0.0.1:5500}") String allowedOriginsStr
    ) {
        if (allowedOriginsStr != null && !allowedOriginsStr.isBlank()) {
            this.allowedOrigins = Arrays.stream(allowedOriginsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        } else {
            this.allowedOrigins = Set.of("http://localhost:5500", "http://127.0.0.1:5500");
        }
    }

    public void validateCsrf(HttpServletRequest request) {
        if (request == null) {
            throw new CsrfValidationException("Request cannot be null for CSRF validation.");
        }

        // 1. Enforce required custom header
        String customHeader = request.getHeader(REQUIRED_HEADER);
        if (customHeader == null || customHeader.isBlank()) {
            logger.warn("CSRF rejection: missing required header '{}'", REQUIRED_HEADER);
            throw new CsrfValidationException("Missing required '" + REQUIRED_HEADER + "' header for state-changing authentication request.");
        }

        // 2. Validate Origin or Referer against allowed origins
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            if (!isAllowedOrigin(origin.trim())) {
                logger.warn("CSRF rejection: Origin '{}' is not in allowed origins: {}", origin, allowedOrigins);
                throw new CsrfValidationException("Origin '" + origin + "' is not permitted.");
            }
            return;
        }

        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            String refererOrigin = extractOrigin(referer);
            if (refererOrigin == null || !isAllowedOrigin(refererOrigin)) {
                logger.warn("CSRF rejection: Referer origin '{}' is not in allowed origins: {}", refererOrigin, allowedOrigins);
                throw new CsrfValidationException("Referer origin '" + refererOrigin + "' is not permitted.");
            }
            return;
        }

        logger.warn("CSRF rejection: both Origin and Referer headers are absent");
        throw new CsrfValidationException("Origin or Referer header is required for CSRF validation.");
    }

    private boolean isAllowedOrigin(String candidate) {
        String cleanCandidate = candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
        return allowedOrigins.contains(cleanCandidate);
    }

    private String extractOrigin(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme != null && host != null) {
                if (port == -1 || (port == 80 && "http".equals(scheme)) || (port == 443 && "https".equals(scheme))) {
                    return scheme + "://" + host;
                }
                return scheme + "://" + host + ":" + port;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }
}


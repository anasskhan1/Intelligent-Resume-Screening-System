package com.system.resume.dto;

import com.system.resume.model.RefreshToken;
import com.system.resume.model.User;

/**
 * Result of refresh token issuance or rotation.
 */
public record RefreshTokenResult(
        String rawRefreshToken,
        RefreshToken tokenEntity,
        User user
) {}


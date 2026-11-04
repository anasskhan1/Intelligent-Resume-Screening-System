package com.system.resume.dto;

/**
 * Internal container transferring the client AuthResponseDto and the raw refresh token
 * meant exclusively for HttpOnly cookie delivery.
 */
public record LoginResult(
        AuthResponseDto authResponse,
        String rawRefreshToken
) {}


package com.system.resume.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Authentication response returning the JWT access token and safe user profile.
 * Never includes refresh tokens, passwords, or secrets.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponseDto {

    private String accessToken;
    private String tokenType = "Bearer";
    private Long expiresIn = 900L;
    private UserResponseDto user;

    public AuthResponseDto() {
    }

    public AuthResponseDto(String accessToken, UserResponseDto user) {
        this.accessToken = accessToken;
        this.tokenType = "Bearer";
        this.expiresIn = 900L;
        this.user = user;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public Long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UserResponseDto getUser() {
        return user;
    }

    public void setUser(UserResponseDto user) {
        this.user = user;
    }

    // Direct profile getters for flat serialization compatibility
    public Long getId() {
        return user != null ? user.id() : null;
    }

    public String getEmail() {
        return user != null ? user.email() : null;
    }

    public String getFirstName() {
        return user != null ? user.firstName() : null;
    }

    public String getLastName() {
        return user != null ? user.lastName() : null;
    }

    public String getFullName() {
        return user != null ? user.fullName() : null;
    }

    public Boolean getIsActive() {
        return user != null ? user.isActive() : null;
    }

    public Set<String> getRoles() {
        return user != null ? user.roles() : null;
    }

    public LocalDateTime getCreatedAt() {
        return user != null ? user.createdAt() : null;
    }
}


package com.system.resume.exception;

/**
 * Exception thrown when a refresh token is invalid, expired, revoked, or compromised.
 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}


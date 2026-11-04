package com.system.resume.exception;

/**
 * Exception thrown when authentication fails due to invalid email or password credentials.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}


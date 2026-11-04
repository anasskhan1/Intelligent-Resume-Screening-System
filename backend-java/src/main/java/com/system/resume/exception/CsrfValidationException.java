package com.system.resume.exception;

/**
 * Exception thrown when CSRF validation fails for state-changing authentication endpoints.
 */
public class CsrfValidationException extends RuntimeException {
    public CsrfValidationException(String message) {
        super(message);
    }
}


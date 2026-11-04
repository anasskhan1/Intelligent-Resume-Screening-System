package com.system.resume.exception;

/**
 * Exception thrown when authentication fails because the user account is deactivated/inactive.
 */
public class AccountInactiveException extends RuntimeException {

    public AccountInactiveException(String message) {
        super(message);
    }
}


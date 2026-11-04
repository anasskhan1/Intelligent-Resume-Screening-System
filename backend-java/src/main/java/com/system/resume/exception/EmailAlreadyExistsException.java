package com.system.resume.exception;

/**
 * Exception thrown when a user registration attempt is made with an email
 * address that is already registered in the system.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}


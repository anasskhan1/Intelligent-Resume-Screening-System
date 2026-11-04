package com.system.resume.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException returns HTTP 413 Payload Too Large with clear client message")
    void testHandleMaxUploadSizeExceeded_Returns413() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(500 * 1024 * 1024);
        ResponseEntity<Map<String, Object>> response = handler.handleMaxUploadSizeExceeded(ex);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(413, response.getBody().get("status"));
        assertEquals("Payload Too Large", response.getBody().get("error"));
        assertTrue(response.getBody().get("message").toString().contains("exceeds configured limits"));
    }

    @Test
    @DisplayName("DataAccessException returns safe HTTP 500 without leaking database credentials, SQL, or internal details")
    void testHandleDataAccessException_Returns500Safe() {
        DataAccessException ex = new DataAccessException("Communications link failure to jdbc:mysql://root:secret@10.0.0.1:3306/resume_screening_db", new SQLException("SELECT * FROM users")) {};
        ResponseEntity<Map<String, Object>> response = handler.handleDataAccessException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().get("status"));
        assertEquals("Internal Server Error", response.getBody().get("error"));

        String clientMessage = response.getBody().get("message").toString();
        assertEquals("A database error occurred while processing the request. Please try again later.", clientMessage);

        // Security assertion: no sensitive details in response payload
        assertFalse(clientMessage.contains("root"));
        assertFalse(clientMessage.contains("secret"));
        assertFalse(clientMessage.contains("SELECT"));
        assertFalse(clientMessage.contains("3306"));
        assertFalse(response.getBody().containsKey("stackTrace"));
    }

    @Test
    @DisplayName("Generic Exception returns safe HTTP 500 without leaking internal exception stack traces")
    void testHandleGenericException_Returns500Safe() {
        RuntimeException ex = new RuntimeException("NullPointerException at com.system.resume.service.InternalService.process(InternalService.java:42)");
        ResponseEntity<Map<String, Object>> response = handler.handleGenericException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().get("status"));
        assertEquals("Internal Server Error", response.getBody().get("error"));
        assertEquals("An unexpected internal server error occurred.", response.getBody().get("message"));

        assertFalse(response.getBody().containsKey("stackTrace"));
    }

    @Test
    @DisplayName("IllegalArgumentException returns HTTP 400 Bad Request")
    void testHandleIllegalArgument_Returns400() {
        IllegalArgumentException ex = new IllegalArgumentException("Unsupported file type '.exe'");
        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().get("status"));
        assertEquals("Unsupported file type '.exe'", response.getBody().get("message"));
    }

    @Test
    @DisplayName("AccessDeniedException returns HTTP 403 Forbidden")
    void testHandleAccessDenied_Returns403() {
        AccessDeniedException ex = new AccessDeniedException("Access denied: Recruiter does not own job");
        ResponseEntity<Map<String, Object>> response = handler.handleAccessDenied(ex);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(403, response.getBody().get("status"));
        assertEquals("Access denied: insufficient permissions to access this resource", response.getBody().get("message"));
    }
}


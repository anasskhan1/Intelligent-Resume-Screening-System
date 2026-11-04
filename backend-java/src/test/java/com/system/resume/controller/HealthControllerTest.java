package com.system.resume.controller;

import com.system.resume.client.AiServiceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private ResultSet resultSet;

    @Test
    @DisplayName("GET /health returns HTTP 200 and status UP when database connectivity succeeds")
    void testHealthCheck_DatabaseHealthy_ReturnsHttp200AndUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(1);

        HealthController controller = new HealthController(dataSource, aiServiceClient, "http://localhost:8000");
        ResponseEntity<Map<String, String>> response = controller.healthCheck();

        assertEquals(HttpStatus.OK, response.getStatusCode(), "Healthy DB must return HTTP 200 OK");
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("UP", response.getBody().get("database"));
        assertEquals("resume-screening-backend", response.getBody().get("service"));

        verify(dataSource, times(1)).getConnection();
        verify(statement, times(1)).executeQuery("SELECT 1");
    }

    @Test
    @DisplayName("GET /health returns HTTP 503 and safe status DOWN when database connection fails")
    void testHealthCheck_DatabaseConnectionFails_ReturnsHttp503AndDown() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("HikariPool-1 - Connection is not available, request timed out after 20000ms"));

        HealthController controller = new HealthController(dataSource, aiServiceClient, "http://localhost:8000");
        ResponseEntity<Map<String, String>> response = controller.healthCheck();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), "Failing DB must return HTTP 503 SERVICE_UNAVAILABLE");
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
        assertEquals("resume-screening-backend", response.getBody().get("service"));

        // Verify no credentials, stack traces, or SQL details are leaked in the client payload
        assertFalse(response.getBody().containsKey("error"));
        assertFalse(response.getBody().containsKey("exception"));
        assertFalse(response.getBody().containsKey("message"));
    }

    @Test
    @DisplayName("GET /health returns HTTP 503 when connection succeeds but SELECT 1 query throws exception")
    void testHealthCheck_DatabaseQueryFails_ReturnsHttp503AndDown() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenThrow(new SQLException("Communications link failure during query"));

        HealthController controller = new HealthController(dataSource, aiServiceClient, "http://localhost:8000");
        ResponseEntity<Map<String, String>> response = controller.healthCheck();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), "Failing query must return HTTP 503");
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
    }

    @Test
    @DisplayName("GET /health returns HTTP 503 safely if DataSource is null")
    void testHealthCheck_NullDataSource_ReturnsHttp503() {
        HealthController controller = new HealthController(null, aiServiceClient, "http://localhost:8000");
        ResponseEntity<Map<String, String>> response = controller.healthCheck();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
    }

    @Test
    @DisplayName("GET /health/ai returns UP when AI service probe succeeds")
    void testAiHealthCheck_Healthy() {
        when(aiServiceClient.isServiceHealthy()).thenReturn(true);

        HealthController controller = new HealthController(dataSource, aiServiceClient, "http://localhost:8000");
        Map<String, String> response = controller.aiHealthCheck();

        assertEquals("UP", response.get("aiServiceStatus"));
        assertEquals("http://localhost:8000/health", response.get("endpoint"));
    }

    @Test
    @DisplayName("GET /health/ai returns DOWN when AI service probe fails")
    void testAiHealthCheck_Unhealthy() {
        when(aiServiceClient.isServiceHealthy()).thenReturn(false);

        HealthController controller = new HealthController(dataSource, aiServiceClient, "http://localhost:8000/");
        Map<String, String> response = controller.aiHealthCheck();

        assertEquals("DOWN", response.get("aiServiceStatus"));
        assertEquals("http://localhost:8000/health", response.get("endpoint"));
    }
}


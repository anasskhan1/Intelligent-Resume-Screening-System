package com.system.resume.controller;

import com.system.resume.client.AiServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * REST controller for health and liveness checks of the Java backend service,
 * verification of database connectivity, and downstream HTTP connectivity to the Python AI service.
 */
@RestController
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final AiServiceClient aiServiceClient;
    private final String aiHealthUrl;

    public HealthController(
            DataSource dataSource,
            AiServiceClient aiServiceClient,
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl
    ) {
        this.dataSource = dataSource;
        this.aiServiceClient = aiServiceClient;
        String normalizedBaseUrl = AiServiceClient.normalizeBaseUrl(baseUrl);
        this.aiHealthUrl = normalizedBaseUrl + "/health";
    }

    /**
     * Liveness and database connectivity health endpoint.
     * Verifies actual database reachability via a lightweight SELECT 1 query.
     *
     * @return 200 OK {"status":"UP","service":"resume-screening-backend","database":"UP"} when healthy,
     *         or 503 SERVICE_UNAVAILABLE {"status":"DOWN","service":"resume-screening-backend","database":"DOWN"} on failure.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        boolean dbHealthy = checkDatabaseHealth();
        if (dbHealthy) {
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "service", "resume-screening-backend",
                    "database", "UP"
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "service", "resume-screening-backend",
                    "database", "DOWN"
            ));
        }
    }

    /**
     * Verification endpoint that calls the Python AI service GET /health via AiServiceClient.
     *
     * @return JSON payload: {"aiServiceStatus":"UP"|"DOWN", "endpoint":"http://localhost:8000/health"}
     */
    @GetMapping("/health/ai")
    public Map<String, String> aiHealthCheck() {
        boolean isHealthy = aiServiceClient.isServiceHealthy();
        return Map.of(
                "aiServiceStatus", isHealthy ? "UP" : "DOWN",
                "endpoint", aiHealthUrl
        );
    }

    private boolean checkDatabaseHealth() {
        if (dataSource == null) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            if (conn == null) {
                return false;
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception ex) {
            logger.warn("Database health check probe failed: {}", ex.getMessage());
            return false;
        }
    }
}

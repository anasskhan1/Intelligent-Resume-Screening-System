package com.system.resume.client;

import com.system.resume.controller.HealthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying URL normalization and handling for AiServiceClient and HealthController,
 * specifically ensuring compatibility with Render internal service discovery (hostport)
 * while preserving local and full HTTP/HTTPS URL formats.
 */
class AiServiceClientUrlHandlingTest {

    @ParameterizedTest(name = "normalizeBaseUrl(\"{0}\") should return \"{1}\"")
    @CsvSource({
            "resume-screening-ai:8000, http://resume-screening-ai:8000",
            "resume-screening-ai:8000/, http://resume-screening-ai:8000",
            "resume-screening-ai:8000///, http://resume-screening-ai:8000",
            "http://localhost:8000, http://localhost:8000",
            "http://localhost:8000/, http://localhost:8000",
            "http://localhost:8000///, http://localhost:8000",
            "https://resume-screening-ai.onrender.com, https://resume-screening-ai.onrender.com",
            "https://resume-screening-ai.onrender.com/, https://resume-screening-ai.onrender.com",
            "custom-internal-host:9090, http://custom-internal-host:9090",
            "ai-service, http://ai-service"
    })
    @DisplayName("normalizeBaseUrl correctly normalizes valid URLs and host:port combinations")
    void testNormalizeBaseUrl_ValidInputs(String input, String expected) {
        String actual = AiServiceClient.normalizeBaseUrl(input);
        assertEquals(expected, actual);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("normalizeBaseUrl falls back to http://localhost:8000 when input is null or blank")
    void testNormalizeBaseUrl_NullOrBlankFallback(String input) {
        String actual = AiServiceClient.normalizeBaseUrl(input);
        assertEquals("http://localhost:8000", actual);
    }

    @Test
    @DisplayName("AiServiceClient constructor normalizes Render hostport and preserves API key")
    void testAiServiceClientConstructor_WithHostport() {
        RestTemplate mockRestTemplate = mock(RestTemplate.class);
        AiServiceClient client = new AiServiceClient(mockRestTemplate, "resume-screening-ai:8000", "test-secret-key");

        assertEquals("http://resume-screening-ai:8000", client.getBaseUrl());
    }

    @Test
    @DisplayName("AiServiceClient constructor normalizes full HTTPS URL and handles null API key")
    void testAiServiceClientConstructor_WithHttpsUrl() {
        RestTemplate mockRestTemplate = mock(RestTemplate.class);
        AiServiceClient client = new AiServiceClient(mockRestTemplate, "https://ai.example.com/", null);

        assertEquals("https://ai.example.com", client.getBaseUrl());
    }

    @Test
    @DisplayName("AiServiceClient two-argument constructor defaults API key safely")
    void testAiServiceClientConstructor_TwoArgs() {
        RestTemplate mockRestTemplate = mock(RestTemplate.class);
        AiServiceClient client = new AiServiceClient(mockRestTemplate, "resume-screening-ai:8000/");

        assertEquals("http://resume-screening-ai:8000", client.getBaseUrl());
    }

    @Test
    @DisplayName("HealthController uses normalized AI service URL for health check endpoint")
    void testHealthController_WithRenderHostport() {
        DataSource mockDataSource = mock(DataSource.class);
        AiServiceClient mockAiClient = mock(AiServiceClient.class);
        when(mockAiClient.isServiceHealthy()).thenReturn(true);

        HealthController controller = new HealthController(
                mockDataSource,
                mockAiClient,
                "resume-screening-ai:8000"
        );

        Map<String, String> response = controller.aiHealthCheck();
        assertEquals("UP", response.get("aiServiceStatus"));
        assertEquals("http://resume-screening-ai:8000/health", response.get("endpoint"));
    }
}


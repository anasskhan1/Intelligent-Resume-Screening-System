package com.system.resume;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ResumeScreeningApplicationTest {

    @Test
    @DisplayName("RestTemplate bean must configure explicit connect (5,000 ms) and read (60,000 ms) timeouts")
    void testRestTemplateConfiguration_ExplicitTimeouts() {
        ResumeScreeningApplication app = new ResumeScreeningApplication();
        RestTemplate restTemplate = app.restTemplate();

        assertNotNull(restTemplate, "RestTemplate bean must not be null");

        ClientHttpRequestFactory requestFactory = restTemplate.getRequestFactory();
        assertInstanceOf(SimpleClientHttpRequestFactory.class, requestFactory,
                "Request factory must be an instance of SimpleClientHttpRequestFactory");

        SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory) requestFactory;

        // Inspect configured timeout fields
        Object connectTimeout = ReflectionTestUtils.getField(factory, "connectTimeout");
        Object readTimeout = ReflectionTestUtils.getField(factory, "readTimeout");

        assertNotNull(connectTimeout, "connectTimeout field must be set");
        assertNotNull(readTimeout, "readTimeout field must be set");

        int connectMs = (connectTimeout instanceof Duration d) ? (int) d.toMillis() : ((Number) connectTimeout).intValue();
        int readMs = (readTimeout instanceof Duration d) ? (int) d.toMillis() : ((Number) readTimeout).intValue();

        assertEquals(5_000, connectMs, "Connect timeout must be configured to exactly 5,000 ms (5 seconds)");
        assertEquals(60_000, readMs, "Read timeout must be configured to exactly 60,000 ms (60 seconds)");
    }
}


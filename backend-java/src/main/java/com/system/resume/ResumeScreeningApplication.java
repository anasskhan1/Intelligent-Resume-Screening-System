package com.system.resume;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Main entry point for the Intelligent Resume Screening and Candidate Ranking backend.
 * Bootstraps Spring Data JPA, Web MVC, and provides HTTP client configuration
 * for communicating with the Python AI microservice.
 */
@SpringBootApplication
public class ResumeScreeningApplication {

    public static final int CONNECT_TIMEOUT_MS = 5_000;
    public static final int READ_TIMEOUT_MS = 60_000;

    public static void main(String[] args) {
        SpringApplication.run(ResumeScreeningApplication.class, args);
    }

    /**
     * Provides a singleton RestTemplate for HTTP communication with the Python AI service.
     * Configured with explicit connect (5,000 ms) and read (60,000 ms) timeouts
     * to prevent worker thread starvation if downstream microservice calls hang.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
        return new RestTemplate(factory);
    }
}


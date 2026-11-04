package com.system.resume.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * HTTP REST Client for communicating with the Python FastAPI AI Microservice.
 * Handles resume parsing, skill extraction, candidate-job matching, and ranking.
 * Strict architectural boundary: Python microservice communicates solely via HTTP.
 */
@Service
public class AiServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public AiServiceClient(
            RestTemplate restTemplate,
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl
    ) {
        this(restTemplate, baseUrl, null);
    }

    public AiServiceClient(
            RestTemplate restTemplate,
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service.api-key:${AI_SERVICE_API_KEY:}}") String apiKey
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : null;
    }

    private void attachServiceAuthHeader(HttpHeaders headers) {
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("X-API-Key", apiKey);
        }
    }

    // =========================================================================
    // Record Contracts matching Python Pydantic Schemas
    // =========================================================================

    public record ExtractedSkill(
            String skill_name,
            String category,
            Double confidence_score
    ) {}

    public record ParseTextRequest(
            String text,
            String file_name
    ) {}

    public record ResumeParseResponse(
            String file_name,
            String parsed_text,
            List<ExtractedSkill> extracted_skills,
            int word_count,
            boolean success,
            String error_message,
            String candidate_name,
            String candidate_email,
            String candidate_phone
    ) {
        public ResumeParseResponse(
                String file_name,
                String parsed_text,
                List<ExtractedSkill> extracted_skills,
                int word_count,
                boolean success,
                String error_message
        ) {
            this(file_name, parsed_text, extracted_skills, word_count, success, error_message, null, null, null);
        }
    }

    public record MatchRequest(
            Long job_id,
            String job_title,
            String job_description,
            List<String> required_skills,
            Integer min_experience_years,
            Long resume_id,
            String candidate_name,
            String resume_text,
            List<String> candidate_skills
    ) {}

    public record MatchResponse(
            Long job_id,
            Long resume_id,
            Double match_score,
            Double skills_score,
            Double experience_score,
            List<String> matched_skills,
            List<String> missing_skills,
            String ai_summary
    ) {}

    public record CandidateProfile(
            Long candidate_id,
            Long resume_id,
            String candidate_name,
            String resume_text,
            List<String> candidate_skills
    ) {}

    public record BatchRankRequest(
            Long job_id,
            String job_title,
            String job_description,
            List<String> required_skills,
            Integer min_experience_years,
            List<CandidateProfile> candidates
    ) {}

    public record RankedCandidate(
            Integer rank_position,
            Long candidate_id,
            Long resume_id,
            String candidate_name,
            Double final_score,
            Double skills_score,
            Double experience_score,
            List<String> matched_skills,
            List<String> missing_skills,
            String ai_summary
    ) {}

    public record BatchRankResponse(
            Long job_id,
            Integer total_candidates,
            List<RankedCandidate> rankings
    ) {}

    // =========================================================================
    // API Methods
    // =========================================================================

    /**
     * Checks liveness and availability of the Python AI service.
     */
    public boolean isServiceHealthy() {
        String url = baseUrl + "/health";
        try {
            HttpHeaders headers = new HttpHeaders();
            attachServiceAuthHeader(headers);
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.warn("AI service health check failed at {}: {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * Uploads a resume binary file (PDF, DOCX, TXT) to the AI service for text and skill extraction.
     */
    public ResumeParseResponse parseDocument(byte[] fileBytes, String filename) {
        String safeFilename = (filename != null && !filename.isBlank()) ? filename : "uploaded_document";

        if (fileBytes == null || fileBytes.length == 0) {
            return new ResumeParseResponse(
                    safeFilename,
                    "",
                    Collections.emptyList(),
                    0,
                    false,
                    "Cannot process empty or null file data."
            );
        }

        String url = baseUrl + "/api/v1/parse-document";
        logger.info("Calling AI service to parse document: {}", safeFilename);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            attachServiceAuthHeader(headers);

            ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return safeFilename;
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<ResumeParseResponse> response = restTemplate.postForEntity(url, requestEntity, ResumeParseResponse.class);

            if (response.getBody() != null) {
                return response.getBody();
            }
            return new ResumeParseResponse(safeFilename, "", Collections.emptyList(), 0, false, "Empty response from AI service.");
        } catch (HttpStatusCodeException e) {
            logger.error("HTTP error {} parsing file '{}': {}", e.getStatusCode(), safeFilename, e.getResponseBodyAsString(), e);
            return new ResumeParseResponse(safeFilename, "", Collections.emptyList(), 0, false, "The AI screening service was unable to process the document format.");
        } catch (ResourceAccessException e) {
            logger.error("Connection failure to AI service at {}: {}", url, e.getMessage(), e);
            return new ResumeParseResponse(safeFilename, "", Collections.emptyList(), 0, false, "The AI screening service is currently unreachable.");
        } catch (Exception e) {
            logger.error("Unexpected error calling AI parse-document for '{}': {}", safeFilename, e.getMessage(), e);
            return new ResumeParseResponse(safeFilename, "", Collections.emptyList(), 0, false, "An unexpected error occurred while parsing the document.");
        }
    }

    /**
     * Sends plain text to the AI service for normalization and skill extraction.
     */
    public ResumeParseResponse parseText(String text, String filename) {
        String safeFilename = (filename != null && !filename.isBlank()) ? filename : "raw_input.txt";

        if (text == null || text.isBlank()) {
            return new ResumeParseResponse(
                    safeFilename,
                    "",
                    Collections.emptyList(),
                    0,
                    false,
                    "Text content cannot be null or empty."
            );
        }

        String url = baseUrl + "/api/v1/parse-text";
        logger.info("Calling AI service to parse text for source: {}", safeFilename);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            attachServiceAuthHeader(headers);

            ParseTextRequest requestBody = new ParseTextRequest(text, safeFilename);
            HttpEntity<ParseTextRequest> requestEntity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<ResumeParseResponse> response = restTemplate.postForEntity(url, requestEntity, ResumeParseResponse.class);
            if (response.getBody() != null) {
                return response.getBody();
            }
            return new ResumeParseResponse(safeFilename, "", Collections.emptyList(), 0, false, "Empty response from AI service.");
        } catch (HttpStatusCodeException e) {
            logger.error("HTTP error {} calling AI parse-text for '{}': {}", e.getStatusCode(), safeFilename, e.getResponseBodyAsString(), e);
            return new ResumeParseResponse(safeFilename, "", Collections.emptyList(), 0, false, "The AI screening service was unable to process the text.");
        } catch (ResourceAccessException e) {
            logger.error("Connection failure to AI service at {}: {}", url, e.getMessage(), e);
            return new ResumeParseResponse(safeFilename, "", Collections.emptyList(), 0, false, "The AI screening service is currently unreachable.");
        } catch (Exception e) {
            logger.error("Unexpected error calling AI parse-text for '{}': {}", safeFilename, e.getMessage(), e);
            return new ResumeParseResponse(safeFilename, "", Collections.emptyList(), 0, false, "An unexpected error occurred while processing resume text.");
        }
    }

    /**
     * Evaluates a single candidate resume against a job description.
     */
    public MatchResponse evaluateMatch(MatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("MatchRequest cannot be null.");
        }

        String url = baseUrl + "/api/v1/match";
        logger.info("Calling AI service to match Job ID {} with Resume ID {}", request.job_id(), request.resume_id());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            attachServiceAuthHeader(headers);

            HttpEntity<MatchRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<MatchResponse> response = restTemplate.postForEntity(url, requestEntity, MatchResponse.class);

            if (response.getBody() != null) {
                return response.getBody();
            }
            logger.error("AI service returned null body for Job ID {} / Resume ID {}", request.job_id(), request.resume_id());
            throw new RuntimeException("The AI match service returned an empty response.");
        } catch (HttpStatusCodeException e) {
            logger.error("HTTP error {} calling AI match for Job ID {} / Resume ID {}: {}",
                    e.getStatusCode(), request.job_id(), request.resume_id(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("The AI match service responded with an error (HTTP " + e.getStatusCode().value() + ").", e);
        } catch (ResourceAccessException e) {
            logger.error("Connection failure to AI service at {} during match evaluation: {}", url, e.getMessage(), e);
            throw new RuntimeException("The AI screening service is currently unreachable.", e);
        } catch (RuntimeException e) {
            // Re-throw already sanitized runtime exceptions without re-wrapping
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error calling AI match for Job ID {} / Resume ID {}: {}", request.job_id(), request.resume_id(), e.getMessage(), e);
            throw new RuntimeException("An unexpected error occurred during match evaluation.", e);
        }
    }

    /**
     * Evaluates and deterministically ranks a batch of candidates against a job description.
     */
    public BatchRankResponse rankCandidates(BatchRankRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("BatchRankRequest cannot be null.");
        }

        int candidateCount = (request.candidates() != null) ? request.candidates().size() : 0;
        String url = baseUrl + "/api/v1/rank";
        logger.info("Calling AI service to rank {} candidate(s) for Job ID {}", candidateCount, request.job_id());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            attachServiceAuthHeader(headers);

            HttpEntity<BatchRankRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<BatchRankResponse> response = restTemplate.postForEntity(url, requestEntity, BatchRankResponse.class);

            if (response.getBody() != null) {
                return response.getBody();
            }
            logger.error("AI service returned null body for candidate ranking for Job ID {}", request.job_id());
            throw new RuntimeException("The AI ranking service returned an empty response.");
        } catch (HttpStatusCodeException e) {
            logger.error("HTTP error {} calling AI rank for Job ID {}: {}",
                    e.getStatusCode(), request.job_id(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("The AI ranking service responded with an error (HTTP " + e.getStatusCode().value() + ").", e);
        } catch (ResourceAccessException e) {
            logger.error("Connection failure to AI service at {} during candidate ranking: {}", url, e.getMessage(), e);
            throw new RuntimeException("The AI screening service is currently unreachable.", e);
        } catch (RuntimeException e) {
            // Re-throw already sanitized runtime exceptions without re-wrapping
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error calling AI rank for Job ID {}: {}", request.job_id(), e.getMessage(), e);
            throw new RuntimeException("An unexpected error occurred during candidate ranking.", e);
        }
    }
}

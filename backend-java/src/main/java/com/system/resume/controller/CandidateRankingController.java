package com.system.resume.controller;

import com.system.resume.dto.CandidateRankingResponseDto;
import com.system.resume.dto.ReviewStatusUpdateDto;
import com.system.resume.service.CandidateExportService;
import com.system.resume.service.CandidateRankingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for candidate batch ranking, leaderboard generation,
 * Top-N selection quota management, recruiter review status updates, and CSV export.
 */
@RestController
@RequestMapping("/api/v1/rankings")
@PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
public class CandidateRankingController {

    private static final Logger logger = LoggerFactory.getLogger(CandidateRankingController.class);
    private static final DateTimeFormatter FILENAME_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final CandidateRankingService candidateRankingService;
    private final CandidateExportService candidateExportService;

    public CandidateRankingController(CandidateRankingService candidateRankingService,
                                      CandidateExportService candidateExportService) {
        this.candidateRankingService = candidateRankingService;
        this.candidateExportService = candidateExportService;
    }

    /**
     * Batch ranks all screened candidates for a target job posting, executes Top-N selection
     * against the minimum score threshold, generates AI decision reasons, preserves prior
     * recruiter overrides, and returns the updated leaderboard.
     *
     * @param jobId target job description ID
     * @param topN optional selection quota (defaults to 10)
     * @param minScore optional minimum match score percentage (defaults to 20.0)
     * @return updated candidate ranking leaderboard
     */
    @PostMapping("/generate/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<List<CandidateRankingResponseDto>> generateRankingsForJob(
            @PathVariable("jobId") Long jobId,
            @RequestParam(name = "topN", defaultValue = "10") Integer topN,
            @RequestParam(name = "minScore", defaultValue = "20.0") Double minScore
    ) {
        if (topN != null && topN <= 0) {
            throw new IllegalArgumentException("Query parameter 'topN' must be greater than zero.");
        }
        if (minScore != null && (minScore < 0.0 || minScore > 100.0)) {
            throw new IllegalArgumentException("Query parameter 'minScore' must be between 0.0 and 100.0 inclusive.");
        }

        logger.info("Generating candidate rankings for Job ID {} with topN={} and minScore={}", jobId, topN, minScore);
        List<CandidateRankingResponseDto> rankings = candidateRankingService.rankCandidatesForJobDto(jobId, topN, minScore);
        return ResponseEntity.ok(rankings);
    }

    /**
     * Retrieves the candidate ranking leaderboard for a job, optionally filtered by review status.
     */
    @GetMapping("/job/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<List<CandidateRankingResponseDto>> getLeaderboardByJob(
            @PathVariable("jobId") Long jobId,
            @RequestParam(name = "reviewStatus", required = false) String reviewStatus
    ) {
        List<CandidateRankingResponseDto> leaderboard = candidateRankingService.getLeaderboardDtosByJob(jobId, reviewStatus);
        return ResponseEntity.ok(leaderboard);
    }

    /**
     * Exports candidate rankings for a target job to an injection-safe, RFC 4180-compliant CSV file.
     * Supports optional reviewStatus filter.
     *
     * @param jobId target job description ID
     * @param reviewStatus optional review status filter (e.g. SELECTED, SHORTLISTED, REJECTED)
     * @return CSV file attachment with UTF-8 BOM
     */
    @GetMapping("/job/{jobId}/export")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<byte[]> exportLeaderboardCsv(
            @PathVariable("jobId") Long jobId,
            @RequestParam(name = "reviewStatus", required = false) String reviewStatus
    ) {
        logger.info("Received request to export CSV for Job ID {} (reviewStatus: {})", jobId, reviewStatus);
        byte[] csvData = candidateExportService.exportLeaderboardCsv(jobId, reviewStatus);

        String timestamp = LocalDateTime.now().format(FILENAME_DATE_FORMATTER);
        String filename = String.format("candidates_job_%d_%s.csv", jobId, timestamp);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(csvData.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(csvData);
    }

    /**
     * Updates recruiter review status (SELECTED, SHORTLISTED, REVIEWED, REJECTED) and feedback notes for a candidate.
     */
    @PatchMapping("/{rankingId}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<CandidateRankingResponseDto> updateReviewStatus(
            @PathVariable("rankingId") Long rankingId,
            @Valid @RequestBody ReviewStatusUpdateDto request
    ) {
        CandidateRankingResponseDto updated = candidateRankingService.updateReviewStatusDto(
                rankingId,
                request.reviewStatus(),
                request.reviewerNotes()
        );
        return ResponseEntity.ok(updated);
    }

    /**
     * Exception handler providing clear error messages for invalid input arguments
     * (e.g. topN <= 0 or minScore outside 0.0 to 100.0).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn("Validation error in CandidateRankingController: {}", ex.getMessage());
        Map<String, Object> errorBody = Map.of(
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", "Bad Request",
                "message", ex.getMessage()
        );
        return ResponseEntity.badRequest().body(errorBody);
    }

    /**
     * Exception handler providing clear 400 Bad Request messages when query parameters
     * cannot be parsed into target types (e.g. string passed for numeric jobId, topN or minScore).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        logger.warn("Type mismatch error in CandidateRankingController parameter '{}': {}",
                ex.getName(), ex.getMessage());
        String expectedType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "valid format";
        String message = String.format("Parameter '%s' must be a valid %s (received: '%s')",
                ex.getName(), expectedType, ex.getValue());
        Map<String, Object> errorBody = Map.of(
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", "Bad Request",
                "message", message
        );
        return ResponseEntity.badRequest().body(errorBody);
    }
}

package com.system.resume.controller;

import com.system.resume.dto.ScreeningRequestDto;
import com.system.resume.dto.ScreeningResponseDto;
import com.system.resume.service.ScreeningService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for candidate resume screening operations against job descriptions,
 * scoring evaluations, and screening history retrieval.
 */
@RestController
@RequestMapping("/api/v1/screening")
@PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
public class ScreeningController {

    private static final Logger logger = LoggerFactory.getLogger(ScreeningController.class);

    private final ScreeningService screeningService;

    public ScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    /**
     * Evaluates and persists semantic matching between a candidate's resume and a job description.
     */
    @PostMapping("/screen")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<ScreeningResponseDto> screenResume(@Valid @RequestBody ScreeningRequestDto request) {
        ScreeningResponseDto response = screeningService.screenCandidateResumeDto(request.jobId(), request.resumeId());
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves a screening result by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<ScreeningResponseDto> getScreeningResultById(@PathVariable("id") Long id) {
        ScreeningResponseDto response = screeningService.getScreeningResultDtoById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves all screening results for a target job posting, ordered by match score descending.
     */
    @GetMapping("/job/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<List<ScreeningResponseDto>> getScreeningResultsByJob(@PathVariable("jobId") Long jobId) {
        List<ScreeningResponseDto> results = screeningService.getScreeningResultDtosByJob(jobId);
        return ResponseEntity.ok(results);
    }

    /**
     * Retrieves all screening results for a specific resume across accessible jobs.
     */
    @GetMapping("/resume/{resumeId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<List<ScreeningResponseDto>> getScreeningResultsByResume(@PathVariable("resumeId") Long resumeId) {
        List<ScreeningResponseDto> results = screeningService.getScreeningResultDtosByResume(resumeId);
        return ResponseEntity.ok(results);
    }

    /**
     * Retrieves the screening result for a specific job and resume combination.
     */
    @GetMapping("/job/{jobId}/resume/{resumeId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<ScreeningResponseDto> getScreeningResultForJobAndResume(
            @PathVariable("jobId") Long jobId,
            @PathVariable("resumeId") Long resumeId
    ) {
        return screeningService.getScreeningResultDto(jobId, resumeId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

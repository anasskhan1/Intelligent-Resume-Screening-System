package com.system.resume.controller;

import com.system.resume.client.AiServiceClient.BatchRankRequest;
import com.system.resume.client.AiServiceClient.BatchRankResponse;
import com.system.resume.client.AiServiceClient.MatchRequest;
import com.system.resume.client.AiServiceClient.MatchResponse;
import com.system.resume.client.AiServiceClient.ParseTextRequest;
import com.system.resume.client.AiServiceClient.ResumeParseResponse;
import com.system.resume.dto.RawTextResumeRequestDto;
import com.system.resume.dto.ResumeResponseDto;
import com.system.resume.dto.ResumeSkillDto;
import com.system.resume.service.CandidateRankingService;
import com.system.resume.service.ResumeService;
import com.system.resume.service.ScreeningService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST Controller exposing resume parsing, skill extraction, candidate screening,
 * batch ranking, and persistent resume document operations.
 *
 * All business logic is delegated to the service layer.
 * All four original ad-hoc screening and ranking endpoints are preserved for backward compatibility.
 */
@RestController
@RequestMapping("/api/v1/resumes")
@PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
public class ResumeController {

    private static final Logger logger = LoggerFactory.getLogger(ResumeController.class);

    private final ResumeService resumeService;
    private final ScreeningService screeningService;
    private final CandidateRankingService candidateRankingService;

    public ResumeController(
            ResumeService resumeService,
            ScreeningService screeningService,
            CandidateRankingService candidateRankingService
    ) {
        this.resumeService = resumeService;
        this.screeningService = screeningService;
        this.candidateRankingService = candidateRankingService;
    }

    // =========================================================================
    // 1. Preserved Ad-Hoc Endpoints (Stateless, delegating to Service Layer)
    // =========================================================================

    /**
     * Parses plain text resume content and extracts skills without persistence.
     */
    @PostMapping("/parse-text")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<ResumeParseResponse> parseText(@RequestBody ParseTextRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(
                    new ResumeParseResponse(
                            request != null && request.file_name() != null ? request.file_name() : "raw_input.txt",
                            "",
                            Collections.emptyList(),
                            0,
                            false,
                            "Text content cannot be null or empty."
                    )
            );
        }

        ResumeParseResponse response = resumeService.parseTextAdHoc(request.text(), request.file_name());
        return ResponseEntity.ok(response);
    }

    /**
     * Uploads and parses a binary resume file (PDF, DOCX, TXT) and extracts skills without persistence.
     */
    @PostMapping(value = "/parse-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<ResumeParseResponse> parseDocument(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    new ResumeParseResponse(
                            file != null ? file.getOriginalFilename() : "uploaded_document",
                            "",
                            Collections.emptyList(),
                            0,
                            false,
                            "Uploaded file cannot be null or empty."
                    )
            );
        }

        String filename = file.getOriginalFilename();
        try {
            byte[] fileBytes = file.getBytes();
            ResumeParseResponse response = resumeService.parseDocumentAdHoc(fileBytes, filename);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            logger.error("Failed to read bytes for uploaded file '{}': {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ResumeParseResponse(
                            filename != null ? filename : "uploaded_document",
                            "",
                            Collections.emptyList(),
                            0,
                            false,
                            "An unexpected error occurred while reading the uploaded file."
                    )
            );
        }
    }

    /**
     * Screens a single candidate resume against a job description without database persistence.
     */
    @PostMapping("/match")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<?> matchCandidate(@RequestBody MatchRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "MatchRequest payload cannot be null."));
        }
        if (request.job_title() == null || request.job_title().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "job_title is required."));
        }
        if (request.job_description() == null || request.job_description().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "job_description is required."));
        }
        if (request.resume_text() == null || request.resume_text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "resume_text is required."));
        }

        try {
            MatchResponse response = screeningService.screenAdHoc(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.error("Failed to evaluate match for Job ID {} / Resume ID {}: {}", request.job_id(), request.resume_id(), e.getMessage(), e);
            HttpStatus status = (e.getMessage() != null && e.getMessage().contains("unreachable"))
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY;
            return ResponseEntity.status(status).body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Match evaluation failed."));
        }
    }

    /**
     * Evaluates and deterministically ranks a batch of candidates for a target job without persistence.
     */
    @PostMapping("/rank")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<?> rankCandidates(@RequestBody BatchRankRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "BatchRankRequest payload cannot be null."));
        }
        if (request.job_title() == null || request.job_title().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "job_title is required."));
        }
        if (request.job_description() == null || request.job_description().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "job_description is required."));
        }
        if (request.candidates() == null || request.candidates().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "candidates list cannot be null or empty."));
        }

        try {
            BatchRankResponse response = candidateRankingService.rankCandidatesAdHoc(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.error("Failed to rank candidates for Job ID {}: {}", request.job_id(), e.getMessage(), e);
            HttpStatus status = (e.getMessage() != null && e.getMessage().contains("unreachable"))
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY;
            return ResponseEntity.status(status).body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Candidate ranking failed."));
        }
    }

    // =========================================================================
    // 2. Database-Backed Persistent Resume Endpoints
    // =========================================================================

    /**
     * Uploads, physically stores, and parses a resume file for a candidate, saving the text and extracted skills.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<ResumeResponseDto> uploadResume(
            @RequestParam("candidateId") Long candidateId,
            @RequestParam("file") MultipartFile file
    ) {
        ResumeResponseDto response = resumeService.uploadAndProcessResumeDto(candidateId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Submits and processes raw resume text for a candidate, saving the text and extracted skills.
     */
    @PostMapping("/raw-text")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<ResumeResponseDto> processRawTextResume(@Valid @RequestBody RawTextResumeRequestDto request) {
        ResumeResponseDto response = resumeService.processRawTextResumeDto(
                request.candidateId(),
                request.text(),
                request.title()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves resume metadata, processing status, and extracted skills by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<ResumeResponseDto> getResumeById(@PathVariable("id") Long id) {
        ResumeResponseDto response = resumeService.getResumeDtoById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves all resumes uploaded for a specific candidate.
     */
    @GetMapping("/candidate/{candidateId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<List<ResumeResponseDto>> getResumesByCandidateId(@PathVariable("candidateId") Long candidateId) {
        List<ResumeResponseDto> responses = resumeService.getResumeDtosByCandidateId(candidateId);
        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves all extracted skills for a specific resume.
     */
    @GetMapping("/{id}/skills")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<List<ResumeSkillDto>> getResumeSkills(@PathVariable("id") Long id) {
        List<ResumeSkillDto> skills = resumeService.getSkillDtosByResumeId(id);
        return ResponseEntity.ok(skills);
    }

    /**
     * Deletes a resume, its associated skills, and physical file from disk.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<Void> deleteResume(@PathVariable("id") Long id) {
        resumeService.deleteResume(id);
        return ResponseEntity.noContent().build();
    }
}

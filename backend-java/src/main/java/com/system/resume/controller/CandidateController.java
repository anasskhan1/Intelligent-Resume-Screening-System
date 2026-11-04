package com.system.resume.controller;

import com.system.resume.dto.CandidateRequestDto;
import com.system.resume.dto.CandidateResponseDto;
import com.system.resume.service.CandidateService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for candidate profile management, deduplication,
 * search queries, and lifecycle operations.
 */
@RestController
@RequestMapping("/api/v1/candidates")
@PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
public class CandidateController {

    private static final Logger logger = LoggerFactory.getLogger(CandidateController.class);

    private final CandidateService candidateService;

    public CandidateController(CandidateService candidateService) {
        this.candidateService = candidateService;
    }

    /**
     * Registers a new candidate profile.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<CandidateResponseDto> createCandidate(@Valid @RequestBody CandidateRequestDto request) {
        CandidateResponseDto response = candidateService.createCandidateDto(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves all candidates or filters by search keyword (first or last name).
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<List<CandidateResponseDto>> getCandidates(
            @RequestParam(name = "search", required = false) String search
    ) {
        List<CandidateResponseDto> candidates = (search != null && !search.isBlank())
                ? candidateService.searchCandidateDtos(search)
                : candidateService.getAllCandidateDtos();
        return ResponseEntity.ok(candidates);
    }

    /**
     * Retrieves a candidate profile by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<CandidateResponseDto> getCandidateById(@PathVariable("id") Long id) {
        CandidateResponseDto response = candidateService.getCandidateDtoById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves a candidate profile by unique email address.
     */
    @GetMapping("/email/{email}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<CandidateResponseDto> getCandidateByEmail(@PathVariable("email") String email) {
        return candidateService.getCandidateDtoByEmail(email)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Updates an existing candidate profile.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<CandidateResponseDto> updateCandidate(
            @PathVariable("id") Long id,
            @Valid @RequestBody CandidateRequestDto request
    ) {
        CandidateResponseDto updated = candidateService.updateCandidateDto(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Deletes a candidate profile, cascading to database records and cleaning up resume files from disk.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<Void> deleteCandidate(@PathVariable("id") Long id) {
        candidateService.deleteCandidate(id);
        return ResponseEntity.noContent().build();
    }
}

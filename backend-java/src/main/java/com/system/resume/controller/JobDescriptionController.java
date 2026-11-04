package com.system.resume.controller;

import com.system.resume.dto.JobAssignmentRequestDto;
import com.system.resume.dto.JobAssignmentResponseDto;
import com.system.resume.dto.JobDescriptionRequestDto;
import com.system.resume.dto.JobDescriptionResponseDto;
import com.system.resume.dto.JobSkillExtractionRequestDto;
import com.system.resume.dto.JobSkillExtractionResponseDto;
import com.system.resume.service.JobDescriptionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for managing job descriptions, required skill specifications,
 * status transitions (ACTIVE/CLOSED), job search queries, and hiring manager assignments.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
public class JobDescriptionController {

    private static final Logger logger = LoggerFactory.getLogger(JobDescriptionController.class);

    private final JobDescriptionService jobDescriptionService;

    public JobDescriptionController(JobDescriptionService jobDescriptionService) {
        this.jobDescriptionService = jobDescriptionService;
    }

    /**
     * Creates a new job posting owned by the authenticated recruiter.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<JobDescriptionResponseDto> createJob(@Valid @RequestBody JobDescriptionRequestDto request) {
        JobDescriptionResponseDto response = jobDescriptionService.createJobDto(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Extracts required skills from raw job description text using the Python AI microservice.
     * Does not persist the job description.
     */
    @PostMapping("/extract-skills")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<JobSkillExtractionResponseDto> extractSkills(
            @Valid @RequestBody JobSkillExtractionRequestDto request
    ) {
        JobSkillExtractionResponseDto response = jobDescriptionService.extractSkillsFromJobText(request.descriptionText());
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves all job postings accessible to the caller (recruiter-owned or hiring-manager-assigned).
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<List<JobDescriptionResponseDto>> getJobs(
            @RequestParam(name = "department", required = false) String department,
            @RequestParam(name = "status", required = false) String status
    ) {
        List<JobDescriptionResponseDto> jobs = jobDescriptionService.getJobDtos(department, status);
        return ResponseEntity.ok(jobs);
    }

    /**
     * Searches job descriptions by keyword in title within caller's accessible scope.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<List<JobDescriptionResponseDto>> searchJobs(
            @RequestParam(name = "keyword") String keyword
    ) {
        List<JobDescriptionResponseDto> jobs = jobDescriptionService.searchJobDtos(keyword);
        return ResponseEntity.ok(jobs);
    }

    /**
     * Retrieves a job description by ID, validating ownership/assignment.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<JobDescriptionResponseDto> getJobById(@PathVariable("id") Long id) {
        JobDescriptionResponseDto response = jobDescriptionService.getJobDtoById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Updates an existing job description, validating caller ownership.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<JobDescriptionResponseDto> updateJob(
            @PathVariable("id") Long id,
            @Valid @RequestBody JobDescriptionRequestDto request
    ) {
        JobDescriptionResponseDto updated = jobDescriptionService.updateJobDto(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Updates the status of a job description (e.g. ACTIVE, INACTIVE, CLOSED).
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<JobDescriptionResponseDto> updateJobStatus(
            @PathVariable("id") Long id,
            @RequestParam("status") String status
    ) {
        JobDescriptionResponseDto updated = jobDescriptionService.updateJobStatusDto(id, status);
        return ResponseEntity.ok(updated);
    }

    /**
     * Deletes a job description by ID, validating caller ownership.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<Void> deleteJob(@PathVariable("id") Long id) {
        jobDescriptionService.deleteJob(id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Hiring Manager Job Assignments Endpoints
    // =========================================================================

    /**
     * Assigns a hiring manager to a job posting.
     * Permitted only to ADMIN or the owning RECRUITER.
     */
    @PostMapping("/{id}/hiring-managers")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<JobAssignmentResponseDto> assignHiringManager(
            @PathVariable("id") Long id,
            @Valid @RequestBody JobAssignmentRequestDto request
    ) {
        JobAssignmentResponseDto response = jobDescriptionService.assignHiringManager(id, request.hiringManagerId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists all hiring managers assigned to a job posting.
     */
    @GetMapping("/{id}/hiring-managers")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER', 'HIRING_MANAGER')")
    public ResponseEntity<List<JobAssignmentResponseDto>> getAssignedHiringManagers(@PathVariable("id") Long id) {
        List<JobAssignmentResponseDto> assignments = jobDescriptionService.getAssignedHiringManagers(id);
        return ResponseEntity.ok(assignments);
    }

    /**
     * Removes an assigned hiring manager from a job posting.
     * Permitted only to ADMIN or the owning RECRUITER.
     */
    @DeleteMapping("/{id}/hiring-managers/{managerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<Void> removeHiringManager(
            @PathVariable("id") Long id,
            @PathVariable("managerId") Long managerId
    ) {
        jobDescriptionService.removeHiringManager(id, managerId);
        return ResponseEntity.noContent().build();
    }
}

package com.system.resume.controller;

import com.system.resume.dto.ResumeBatchDetailResponseDto;
import com.system.resume.dto.ResumeBatchFileResponseDto;
import com.system.resume.dto.ResumeBatchResponseDto;
import com.system.resume.service.BatchResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST Controller exposing endpoints for bulk resume uploads, batch progress tracking,
 * and individual file outcome inspections.
 *
 * Execution Note:
 * Batch uploads currently execute synchronously on the request thread, sequentially
 * processing and parsing each file up to the 100-file limit. Once all files have been
 * evaluated, the complete batch summary and per-file outcomes are returned in the response.
 */
@RestController
@RequestMapping("/api/v1/resumes/batches")
@PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
public class ResumeBatchController {

    private static final Logger logger = LoggerFactory.getLogger(ResumeBatchController.class);
    private static final int MAX_FILES_PER_BATCH = 100;
    private static final long MAX_BATCH_TOTAL_SIZE_BYTES = 500L * 1024 * 1024; // 500 MB aggregate batch limit

    private final BatchResumeService batchResumeService;

    public ResumeBatchController(BatchResumeService batchResumeService) {
        this.batchResumeService = batchResumeService;
    }

    /**
     * Uploads and processes a batch of resume files (up to 100 files).
     *
     * @param files list of multipart files
     * @param batchName optional user-supplied name for the batch
     * @param jobDescriptionId optional job description ID for automated screening
     * @return 201 Created with full batch detail on completion, or 400/500 on validation/system failure
     */
    @PostMapping(value = {"", "/upload"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<?> uploadResumeBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "batchName", required = false) String batchName,
            @RequestParam(value = "jobDescriptionId", required = false) Long jobDescriptionId
    ) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one resume file must be uploaded."));
        }

        // Filter out empty or unselected file items
        List<MultipartFile> validFiles = files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();

        if (validFiles.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Uploaded files cannot be null or empty."));
        }

        if (validFiles.size() > MAX_FILES_PER_BATCH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Batch size exceeds maximum limit of " + MAX_FILES_PER_BATCH + " files (received " + validFiles.size() + ")."
            ));
        }

        long totalBatchBytes = validFiles.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalBatchBytes > MAX_BATCH_TOTAL_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "error", "Total batch upload size exceeds maximum limit of 500 MB (received " + (totalBatchBytes / (1024 * 1024)) + " MB)."
            ));
        }

        try {
            logger.info("Received async batch upload request: {} files, batchName='{}', jobDescriptionId={}",
                    validFiles.size(), batchName, jobDescriptionId);

            ResumeBatchResponseDto result = batchResumeService.acceptAndStageBatch(validFiles, batchName, jobDescriptionId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);

        } catch (IllegalArgumentException e) {
            logger.warn("Validation error during batch upload: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", sanitizeClientMessage(e.getMessage())));
        } catch (IllegalStateException e) {
            logger.warn("Server capacity limit during batch upload: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", sanitizeClientMessage(e.getMessage())));
        } catch (Exception e) {
            logger.error("Unexpected error during batch upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "An unexpected error occurred while processing the resume batch."
            ));
        }
    }

    /**
     * Retrieves all resume batches ordered by creation timestamp descending.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<List<ResumeBatchResponseDto>> getAllBatches() {
        List<ResumeBatchResponseDto> batches = batchResumeService.getAllBatchesDto();
        return ResponseEntity.ok(batches);
    }

    /**
     * Retrieves comprehensive batch details including summary and list of all file outcomes.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<?> getBatchDetails(@PathVariable("id") Long id) {
        try {
            ResumeBatchDetailResponseDto detail = batchResumeService.getBatchDetailDto(id);
            return ResponseEntity.ok(detail);
        } catch (IllegalArgumentException e) {
            logger.warn("Batch lookup failed for ID {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Resume batch not found with ID: " + id
            ));
        }
    }

    /**
     * Retrieves high-level batch summary and progress status.
     */
    @GetMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<?> getBatchStatus(@PathVariable("id") Long id) {
        try {
            ResumeBatchResponseDto summary = batchResumeService.getBatchDto(id);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            logger.warn("Batch status lookup failed for ID {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Resume batch not found with ID: " + id
            ));
        }
    }

    /**
     * Retrieves list of all file outcomes, hashes, statuses, and error messages for a batch.
     */
    @GetMapping("/{id}/files")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECRUITER')")
    public ResponseEntity<?> getBatchFiles(@PathVariable("id") Long id) {
        try {
            List<ResumeBatchFileResponseDto> files = batchResumeService.getBatchFilesDto(id);
            return ResponseEntity.ok(files);
        } catch (IllegalArgumentException e) {
            logger.warn("Batch files lookup failed for ID {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Resume batch not found with ID: " + id
            ));
        }
    }

    /**
     * Sanitizes messages returned to the client to avoid leaking server filesystem paths.
     */
    private String sanitizeClientMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Invalid request parameters.";
        }
        return message.replaceAll("(?i)[a-z]:\\\\[^\\s,;]+", "[path]")
                      .replaceAll("(?i)/[^\\s,;]+", "[path]");
    }
}


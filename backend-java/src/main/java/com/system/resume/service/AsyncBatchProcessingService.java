package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.client.AiServiceClient.ResumeParseResponse;
import com.system.resume.model.*;
import com.system.resume.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring-managed asynchronous processing service for resume batches.
 * Executes staged batches on managed thread pool with strict failure isolation,
 * zero long-running transactions, and Python AI HTTP calls made outside DB transactions.
 */
@Service
public class AsyncBatchProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncBatchProcessingService.class);

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}"
    );

    private final ResumeBatchRepository resumeBatchRepository;
    private final ResumeBatchFileRepository resumeBatchFileRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeSkillRepository resumeSkillRepository;
    private final CandidateService candidateService;
    private final FileStorageService fileStorageService;
    private final AiServiceClient aiServiceClient;
    private final ScreeningService screeningService;
    private final TransactionTemplate transactionTemplate;

    public AsyncBatchProcessingService(
            ResumeBatchRepository resumeBatchRepository,
            ResumeBatchFileRepository resumeBatchFileRepository,
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            CandidateService candidateService,
            FileStorageService fileStorageService,
            AiServiceClient aiServiceClient,
            ScreeningService screeningService,
            PlatformTransactionManager transactionManager
    ) {
        this.resumeBatchRepository = resumeBatchRepository;
        this.resumeBatchFileRepository = resumeBatchFileRepository;
        this.resumeRepository = resumeRepository;
        this.resumeSkillRepository = resumeSkillRepository;
        this.candidateService = candidateService;
        this.fileStorageService = fileStorageService;
        this.aiServiceClient = aiServiceClient;
        this.screeningService = screeningService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Asynchronously processes all staged files in the specified batch.
     * Invoked via Spring-managed thread pool 'batchProcessingExecutor'.
     *
     * @param batchId target batch identifier
     * @param jobDescriptionId optional job ID for automated candidate screening
     */
    @Async("batchProcessingExecutor")
    public void processBatchAsync(Long batchId, Long jobDescriptionId) {
        logger.info("Starting background processing for batch ID: {}", batchId);

        // Safeguard 2: Duplicate execution protection
        Optional<ResumeBatch> batchOpt = resumeBatchRepository.findById(batchId);
        if (batchOpt.isEmpty()) {
            logger.warn("Batch ID {} not found. Aborting async processing.", batchId);
            return;
        }

        ResumeBatch batch = batchOpt.get();
        if ("COMPLETED".equals(batch.getStatus()) || "FAILED".equals(batch.getStatus()) || "PARTIAL_SUCCESS".equals(batch.getStatus())) {
            logger.warn("Batch ID {} is already in terminal status '{}'. Skipping redundant execution.", batchId, batch.getStatus());
            return;
        }

        List<ResumeBatchFile> batchFiles = resumeBatchFileRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        Set<String> seenHashesInBatch = new HashSet<>();

        // Reconcile already processed hashes from earlier runs/restarts
        for (ResumeBatchFile bf : batchFiles) {
            if (("PARSED".equals(bf.getStatus()) || "DUPLICATE".equals(bf.getStatus())) && bf.getFileHash() != null) {
                seenHashesInBatch.add(bf.getFileHash());
            }
        }

        for (ResumeBatchFile batchFile : batchFiles) {
            // Process files that are PENDING or were interrupted in PROCESSING
            if ("PARSED".equals(batchFile.getStatus()) || "DUPLICATE".equals(batchFile.getStatus()) || "FAILED".equals(batchFile.getStatus())) {
                continue;
            }

            processSingleStagedFile(batchId, batchFile, jobDescriptionId, seenHashesInBatch);
        }

        // Finalize batch status after all files have reached terminal outcomes
        finalizeBatchStatus(batchId);
    }

    /**
     * Processes a single staged file with strict failure isolation.
     */
    public void processSingleStagedFile(
            Long batchId,
            ResumeBatchFile batchFile,
            Long jobDescriptionId,
            Set<String> seenHashesInBatch
    ) {
        final Long batchFileId = batchFile.getId();
        final String originalFilename = batchFile.getFileName();
        final String storedPath = batchFile.getFilePath();
        final String fileHash = batchFile.getFileHash();

        try {
            // 1. Mark file status as PROCESSING
            transactionTemplate.executeWithoutResult(status -> {
                resumeBatchFileRepository.findById(batchFileId).ifPresent(bf -> {
                    bf.setStatus("PROCESSING");
                    resumeBatchFileRepository.save(bf);
                });
            });

            // 2. Validate physical file exists on disk
            if (storedPath == null || !new File(storedPath).exists()) {
                recordBatchFileFailure(batchId, batchFileId, fileHash, null, "Staged file not found on disk: " + storedPath);
                return;
            }

            // 3. SHA-256 Duplicate Check
            if (resumeRepository.existsByFileHash(fileHash) || seenHashesInBatch.contains(fileHash)) {
                logger.warn("Duplicate resume content detected for file '{}' (hash: {})", originalFilename, fileHash);
                fileStorageService.deleteFile(storedPath);
                recordBatchFileDuplicate(batchId, batchFileId, fileHash);
                return;
            }
            seenHashesInBatch.add(fileHash);

            // 4. Read bytes from disk
            byte[] fileBytes;
            try {
                fileBytes = fileStorageService.loadFileAsBytes(storedPath);
            } catch (Exception e) {
                fileStorageService.deleteFile(storedPath);
                recordBatchFileFailure(batchId, batchFileId, fileHash, null, "Failed to read staged file: " + e.getMessage());
                return;
            }

            // 5. Call Python AI microservice OUTSIDE any DB transaction
            ResumeParseResponse parseResponse;
            try {
                parseResponse = aiServiceClient.parseDocument(fileBytes, originalFilename);
            } catch (Exception e) {
                fileStorageService.deleteFile(storedPath);
                recordBatchFileFailure(batchId, batchFileId, fileHash, null, "AI service error: " + e.getMessage());
                return;
            }

            if (parseResponse == null || !parseResponse.success()) {
                fileStorageService.deleteFile(storedPath);
                String errorMsg = (parseResponse != null && parseResponse.error_message() != null)
                        ? parseResponse.error_message()
                        : "AI document parsing failed.";
                recordBatchFileFailure(batchId, batchFileId, fileHash, null, errorMsg);
                return;
            }

            // 6. DB persistence in a dedicated short transaction (Candidate, Resume, Skills, BatchFile status)
            final ResumeParseResponse finalResponse = parseResponse;
            final long fileSize = fileBytes.length;
            final String fileType = inferContentType(originalFilename);
            final Long[] savedResumeIdHolder = new Long[1];

            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Candidate candidate = resolveCandidateFromResume(finalResponse, originalFilename, fileHash);

                    Resume resume = new Resume(
                            candidate,
                            originalFilename,
                            storedPath,
                            fileHash,
                            fileType,
                            fileSize,
                            finalResponse.parsed_text(),
                            "PARSED"
                    );
                    Resume savedResume = resumeRepository.save(resume);
                    savedResumeIdHolder[0] = savedResume.getId();

                    if (finalResponse.extracted_skills() != null && !finalResponse.extracted_skills().isEmpty()) {
                        List<ResumeSkill> skills = finalResponse.extracted_skills().stream()
                                .map(s -> new ResumeSkill(
                                        savedResume,
                                        s.skill_name(),
                                        s.category(),
                                        s.confidence_score() != null ? BigDecimal.valueOf(s.confidence_score()) : BigDecimal.ZERO
                                ))
                                .toList();
                        resumeSkillRepository.saveAll(skills);
                    }

                    ResumeBatchFile managedBf = resumeBatchFileRepository.findById(batchFileId)
                            .orElseThrow(() -> new IllegalStateException("Batch file not found: " + batchFileId));
                    managedBf.setStatus("PARSED");
                    managedBf.setErrorMessage(null);
                    resumeBatchFileRepository.save(managedBf);

                    incrementBatchProcessedCount(batchId);
                });

                logger.info("Successfully persisted and parsed batch file ID {} ('{}')", batchFileId, originalFilename);

            } catch (Exception ex) {
                handlePersistenceException(batchId, batchFileId, originalFilename, fileHash, storedPath, ex);
                return;
            }

            // 7. Auto-screening executed completely OUTSIDE and AFTER the persistence transaction commit.
            // Screening failure must NEVER roll back an already-persisted resume.
            Long savedResumeId = savedResumeIdHolder[0];
            if (jobDescriptionId != null && savedResumeId != null) {
                try {
                    screeningService.screenCandidateResumeForBatch(batchId, jobDescriptionId, savedResumeId);
                    logger.info("Auto-screening completed for batch file ID {} (resume ID {}) against job ID {}",
                            batchFileId, savedResumeId, jobDescriptionId);
                } catch (Exception ex) {
                    logger.warn("Auto-screening failed for resume ID {} on job ID {} (batch ID {}): {}. "
                            + "Resume remains successfully parsed and stored.",
                            savedResumeId, jobDescriptionId, batchId, ex.getMessage());
                }
            }

        } catch (Exception ex) {
            logger.error("Unhandled exception processing batch file ID {} ('{}'): {}",
                    batchFileId, originalFilename, ex.getMessage(), ex);
            recordBatchFileFailure(batchId, batchFileId, fileHash, storedPath, "Processing failure: " + ex.getMessage());
        }
    }

    /**
     * Finalizes overall batch status once all files have reached terminal outcomes.
     */
    public void finalizeBatchStatus(Long batchId) {
        transactionTemplate.executeWithoutResult(status -> {
            ResumeBatch batch = resumeBatchRepository.findById(batchId)
                    .orElseThrow(() -> new IllegalStateException("Batch not found with ID: " + batchId));

            long failedCount = resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "FAILED");
            long parsedCount = resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PARSED");
            long duplicateCount = resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "DUPLICATE");
            long pendingCount = resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PENDING")
                    + resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PROCESSING");
            long total = batch.getTotalFiles();

            if (pendingCount > 0) {
                logger.warn("Batch ID {} still has {} non-terminal files; deferring completion.", batchId, pendingCount);
                return;
            }

            String finalStatus;
            if (parsedCount == 0 && duplicateCount == 0 && failedCount > 0) {
                finalStatus = "FAILED";
            } else if (failedCount > 0) {
                finalStatus = "PARTIAL_SUCCESS";
            } else {
                finalStatus = "COMPLETED";
            }

            batch.setStatus(finalStatus);
            batch.setProcessedFiles((int) total);
            resumeBatchRepository.save(batch);
            logger.info("Batch ID {} finalized with status: {} (parsed={}, duplicate={}, failed={})",
                    batchId, finalStatus, parsedCount, duplicateCount, failedCount);
        });
    }

    private void handlePersistenceException(
            Long batchId,
            Long batchFileId,
            String originalFilename,
            String fileHash,
            String storedPath,
            Exception originalEx
    ) {
        boolean dbReachable = false;
        Optional<Resume> existingRecordOpt = Optional.empty();

        try {
            existingRecordOpt = resumeRepository.findByFileHash(fileHash);
            dbReachable = true;
        } catch (Exception queryEx) {
            logger.warn("Database query failed while verifying persistence outcome for file '{}': {}",
                    originalFilename, queryEx.getMessage());
        }

        if (dbReachable && existingRecordOpt.isPresent()) {
            Resume existingRecord = existingRecordOpt.get();
            if (storedPath != null && storedPath.equals(existingRecord.getFilePath())) {
                // Confirmed committed
                logger.warn("Resume record for hash {} confirmed committed with path '{}'. Preserving file.", fileHash, storedPath);
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        resumeBatchFileRepository.findById(batchFileId).ifPresent(batchFile -> {
                            if (!"PARSED".equals(batchFile.getStatus())) {
                                batchFile.setStatus("PARSED");
                                batchFile.setErrorMessage(null);
                                resumeBatchFileRepository.save(batchFile);
                                incrementBatchProcessedCount(batchId);
                            }
                        });
                    });
                } catch (Exception updateEx) {
                    logger.error("Failed to update batch file status to PARSED: {}", updateEx.getMessage());
                }
            } else {
                // Duplicate
                logger.warn("Duplicate resume hash confirmed for file '{}'. Deleting newly uploaded file.", originalFilename);
                fileStorageService.deleteFile(storedPath);
                recordBatchFileDuplicate(batchId, batchFileId, fileHash);
            }
        } else if (!dbReachable) {
            // Uncertain
            logger.warn("Database outcome uncertain for file '{}'. Deferring cleanup.", originalFilename);
            recordBatchFileFailure(batchId, batchFileId, fileHash, storedPath,
                    "Database outcome uncertain: " + originalEx.getMessage());
        } else {
            // Rolled back
            logger.error("Database confirmed rolled back for file '{}'. Deleting orphaned file.", originalFilename);
            fileStorageService.deleteFile(storedPath);
            recordBatchFileFailure(batchId, batchFileId, fileHash, null,
                    "Database persistence failure: " + originalEx.getMessage());
        }
    }

    private Candidate resolveCandidateFromResume(ResumeParseResponse parseResponse, String filename, String fileHash) {
        String email = (parseResponse != null && parseResponse.candidate_email() != null && !parseResponse.candidate_email().isBlank())
                ? parseResponse.candidate_email().trim().toLowerCase()
                : null;

        if (email == null && parseResponse != null && parseResponse.parsed_text() != null && !parseResponse.parsed_text().isBlank()) {
            Matcher matcher = EMAIL_PATTERN.matcher(parseResponse.parsed_text());
            if (matcher.find()) {
                email = matcher.group().toLowerCase().trim();
            }
        }

        if (email == null || email.isBlank()) {
            String hashPrefix = (fileHash != null && fileHash.length() >= 12) ? fileHash.substring(0, 12) : "auto";
            email = "candidate_" + hashPrefix + "@batch.local";
        }

        String firstName = null;
        String lastName = null;

        if (parseResponse != null && parseResponse.candidate_name() != null && !parseResponse.candidate_name().isBlank()) {
            String cleanAiName = parseResponse.candidate_name().trim();
            String[] nameParts = cleanAiName.split("\\s+");
            if (nameParts.length > 0 && !nameParts[0].isBlank()) {
                firstName = nameParts[0].trim();
                lastName = nameParts.length > 1
                        ? String.join(" ", Arrays.copyOfRange(nameParts, 1, nameParts.length)).trim()
                        : "Profile";
            }
        }

        if (firstName == null || firstName.isBlank()) {
            String cleanName = filename.replaceAll("(?i)\\.(pdf|docx|txt)$", "")
                    .replaceAll("[_-]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            String[] nameParts = cleanName.split("\\s+");
            firstName = nameParts.length > 0 && !nameParts[0].isBlank() ? nameParts[0] : "Candidate";
            lastName = nameParts.length > 1 ? String.join(" ", Arrays.copyOfRange(nameParts, 1, nameParts.length)) : "Profile";
        }

        String phone = (parseResponse != null && parseResponse.candidate_phone() != null && !parseResponse.candidate_phone().isBlank())
                ? parseResponse.candidate_phone().trim()
                : null;

        return candidateService.getOrCreateCandidate(email, firstName, lastName, phone);
    }

    private void recordBatchFileFailure(Long batchId, Long batchFileId, String fileHash, String filePath, String errorMessage) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                resumeBatchFileRepository.findById(batchFileId).ifPresent(batchFile -> {
                    if (fileHash != null) batchFile.setFileHash(fileHash);
                    if (filePath != null) batchFile.setFilePath(filePath);
                    batchFile.setStatus("FAILED");
                    batchFile.setErrorMessage(errorMessage);
                    resumeBatchFileRepository.save(batchFile);
                });
                incrementBatchProcessedCount(batchId);
            });
        } catch (Exception e) {
            logger.error("Failed to record failure for batch file ID {}: {}", batchFileId, e.getMessage(), e);
        }
    }

    private void recordBatchFileDuplicate(Long batchId, Long batchFileId, String fileHash) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                resumeBatchFileRepository.findById(batchFileId).ifPresent(batchFile -> {
                    batchFile.setFileHash(fileHash);
                    batchFile.setStatus("DUPLICATE");
                    batchFile.setErrorMessage("Resume content already exists in system (hash: " + fileHash + ").");
                    resumeBatchFileRepository.save(batchFile);
                });
                incrementBatchProcessedCount(batchId);
            });
        } catch (Exception e) {
            logger.error("Failed to record duplicate for batch file ID {}: {}", batchFileId, e.getMessage(), e);
        }
    }

    private void incrementBatchProcessedCount(Long batchId) {
        resumeBatchRepository.findById(batchId).ifPresent(batch -> {
            batch.setProcessedFiles(batch.getProcessedFiles() + 1);
            resumeBatchRepository.save(batch);
        });
    }

    private String inferContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}


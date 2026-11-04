package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.client.AiServiceClient.ExtractedSkill;
import com.system.resume.client.AiServiceClient.ResumeParseResponse;
import com.system.resume.dto.ResumeBatchDetailResponseDto;
import com.system.resume.dto.ResumeBatchFileResponseDto;
import com.system.resume.dto.ResumeBatchResponseDto;
import com.system.resume.model.Candidate;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeBatch;
import com.system.resume.model.ResumeBatchFile;
import com.system.resume.model.ResumeSkill;
import com.system.resume.repository.JobDescriptionRepository;
import com.system.resume.repository.ResumeBatchFileRepository;
import com.system.resume.repository.ResumeBatchRepository;
import com.system.resume.repository.ResumeRepository;
import com.system.resume.repository.ResumeSkillRepository;
import com.system.resume.security.SecurityAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service managing bulk resume uploads, batch tracking, content deduplication,
 * candidate linking, and asynchronous-ready independent resume processing.
 *
 * Strict failure isolation:
 * - Each file is processed in its own independent database transaction.
 * - One file's failure (corrupt file, parse error, or network timeout) never aborts other files.
 * - Deduplication is enforced via SHA-256 before disk writing or database insert.
 * - Batch status transitions accurately between PENDING, PROCESSING, COMPLETED, PARTIAL_SUCCESS, and FAILED.
 */
@Service
public class BatchResumeService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(BatchResumeService.class);

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}"
    );

    private final ResumeBatchRepository resumeBatchRepository;
    private final ResumeBatchFileRepository resumeBatchFileRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeSkillRepository resumeSkillRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final CandidateService candidateService;
    private final FileStorageService fileStorageService;
    private final AiServiceClient aiServiceClient;
    private final ScreeningService screeningService;
    private final AsyncBatchProcessingService asyncBatchProcessingService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final SecurityAuthorizationService securityAuthorizationService;

    @Autowired
    public BatchResumeService(
            ResumeBatchRepository resumeBatchRepository,
            ResumeBatchFileRepository resumeBatchFileRepository,
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            JobDescriptionRepository jobDescriptionRepository,
            CandidateService candidateService,
            FileStorageService fileStorageService,
            AiServiceClient aiServiceClient,
            ScreeningService screeningService,
            AsyncBatchProcessingService asyncBatchProcessingService,
            PlatformTransactionManager transactionManager,
            SecurityAuthorizationService securityAuthorizationService
    ) {
        this.resumeBatchRepository = resumeBatchRepository;
        this.resumeBatchFileRepository = resumeBatchFileRepository;
        this.resumeRepository = resumeRepository;
        this.resumeSkillRepository = resumeSkillRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.candidateService = candidateService;
        this.fileStorageService = fileStorageService;
        this.aiServiceClient = aiServiceClient;
        this.screeningService = screeningService;
        this.asyncBatchProcessingService = asyncBatchProcessingService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.securityAuthorizationService = securityAuthorizationService;
    }

    public BatchResumeService(
            ResumeBatchRepository resumeBatchRepository,
            ResumeBatchFileRepository resumeBatchFileRepository,
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            JobDescriptionRepository jobDescriptionRepository,
            CandidateService candidateService,
            FileStorageService fileStorageService,
            AiServiceClient aiServiceClient,
            ScreeningService screeningService,
            AsyncBatchProcessingService asyncBatchProcessingService,
            PlatformTransactionManager transactionManager
    ) {
        this(resumeBatchRepository, resumeBatchFileRepository, resumeRepository, resumeSkillRepository,
                jobDescriptionRepository, candidateService, fileStorageService, aiServiceClient,
                screeningService, asyncBatchProcessingService, transactionManager, null);
    }

    @Override
    public void run(ApplicationArguments args) {
        recoverInterruptedBatches();
    }

    /**
     * Recovery handler executed on application startup.
     * Identifies batches stuck in PROCESSING status and reconciles their file states.
     */
    public void recoverInterruptedBatches() {
        try {
            List<ResumeBatch> processingBatches = resumeBatchRepository.findByStatus("PROCESSING");
            if (processingBatches.isEmpty()) {
                return;
            }
            logger.info("Startup recovery: Found {} batch(es) in PROCESSING status. Initiating safe reconciliation...", processingBatches.size());
            for (ResumeBatch batch : processingBatches) {
                Long batchId = batch.getId();
                List<ResumeBatchFile> files = resumeBatchFileRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
                long pendingOrProcessing = files.stream()
                        .filter(f -> "PENDING".equals(f.getStatus()) || "PROCESSING".equals(f.getStatus()))
                        .count();

                if (pendingOrProcessing == 0) {
                    logger.info("Batch ID {} has all files in terminal status. Finalizing batch state...", batchId);
                    asyncBatchProcessingService.finalizeBatchStatus(batchId);
                } else {
                    boolean canResume = true;
                    for (ResumeBatchFile bf : files) {
                        if ("PENDING".equals(bf.getStatus()) || "PROCESSING".equals(bf.getStatus())) {
                            if (bf.getFilePath() == null || !new File(bf.getFilePath()).exists()) {
                                canResume = false;
                                break;
                            }
                        }
                    }
                    if (canResume) {
                        logger.info("Restart-safe recovery: Resuming async background processing for batch ID: {}", batchId);
                        Long jobId = batch.getJobDescription() != null ? batch.getJobDescription().getId() : null;
                        asyncBatchProcessingService.processBatchAsync(batchId, jobId);
                    } else {
                        logger.warn("Batch ID {} has missing physical files after restart. Marking pending files as FAILED...", batchId);
                        transactionTemplate.executeWithoutResult(status -> {
                            for (ResumeBatchFile bf : files) {
                                if ("PENDING".equals(bf.getStatus()) || "PROCESSING".equals(bf.getStatus())) {
                                    bf.setStatus("FAILED");
                                    bf.setErrorMessage("Processing interrupted by application restart and physical file is unavailable.");
                                    resumeBatchFileRepository.save(bf);
                                }
                            }
                        });
                        asyncBatchProcessingService.finalizeBatchStatus(batchId);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error during startup batch recovery: {}", e.getMessage(), e);
        }
    }

    private record StagedFileInfo(String originalFilename, String storedPath, String fileHash) {}

    /**
     * Staging phase: Safely validates, stores physical files to disk, initializes DB records,
     * and submits processing to the background executor. Returns HTTP 202-ready DTO.
     */
    public ResumeBatchResponseDto acceptAndStageBatch(List<MultipartFile> files, String batchName, Long jobDescriptionId) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Cannot process an empty or null file batch.");
        }

        final JobDescription targetJob = (jobDescriptionId != null)
                ? jobDescriptionRepository.findById(jobDescriptionId)
                        .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobDescriptionId))
                : null;
        if (targetJob != null && securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageJob(targetJob);
        }

        final String effectiveBatchName = (batchName != null && !batchName.isBlank())
                ? batchName.trim()
                : "Batch_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // Safeguard 3: Track all physical files written to disk so they can be completely purged on any error
        List<StagedFileInfo> stagedFiles = new ArrayList<>();
        List<String> storedPaths = new ArrayList<>();

        try {
            for (MultipartFile file : files) {
                byte[] bytes = file.getBytes();
                String fileHash = fileStorageService.computeSha256(bytes);
                String storedPath = fileStorageService.storeFile(file);
                storedPaths.add(storedPath);
                String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
                stagedFiles.add(new StagedFileInfo(originalFilename, storedPath, fileHash));
            }
        } catch (Exception e) {
            logger.error("Failed to stage physical files on disk. Cleaning up {} stored file(s): {}", storedPaths.size(), e.getMessage());
            for (String path : storedPaths) {
                fileStorageService.deleteFile(path);
            }
            throw new RuntimeException("Failed to stage uploaded files: " + e.getMessage(), e);
        }

        // Initialize ResumeBatch and ResumeBatchFile records in a single dedicated short transaction
        final ResumeBatch savedBatch;
        try {
            savedBatch = transactionTemplate.execute(status -> {
                ResumeBatch batch = new ResumeBatch(effectiveBatchName, stagedFiles.size(), 0, "PROCESSING");
                batch.setJobDescription(targetJob);
                if (securityAuthorizationService != null) {
                    batch.setUploadedBy(securityAuthorizationService.getCurrentUser());
                }
                ResumeBatch persistedBatch = resumeBatchRepository.save(batch);

                for (StagedFileInfo staged : stagedFiles) {
                    ResumeBatchFile batchFile = new ResumeBatchFile(
                            persistedBatch,
                            staged.originalFilename(),
                            staged.fileHash(),
                            staged.storedPath(),
                            "PENDING"
                    );
                    resumeBatchFileRepository.save(batchFile);
                }
                return persistedBatch;
            });
        } catch (Exception e) {
            logger.error("Failed to initialize database records for batch. Purging {} stored file(s): {}", storedPaths.size(), e.getMessage());
            for (String path : storedPaths) {
                fileStorageService.deleteFile(path);
            }
            throw new RuntimeException("Database initialization failure for batch: " + e.getMessage(), e);
        }

        // Safeguard 1: Safe executor rejection with complete cleanup of unaccepted batches
        try {
            asyncBatchProcessingService.processBatchAsync(savedBatch.getId(), jobDescriptionId);
            logger.info("Batch ID {} successfully staged and submitted for background execution ({} files).",
                    savedBatch.getId(), stagedFiles.size());
        } catch (RejectedExecutionException e) {
            logger.error("Task executor saturated. Rejecting batch ID {} and cleaning up staged resources: {}",
                    savedBatch.getId(), e.getMessage());
            for (String path : storedPaths) {
                fileStorageService.deleteFile(path);
            }
            transactionTemplate.executeWithoutResult(status -> {
                resumeBatchRepository.deleteById(savedBatch.getId());
            });
            throw new IllegalStateException("Server is temporarily busy. Maximum concurrent processing limit reached. Please try again shortly.");
        }

        return ResumeBatchResponseDto.fromEntity(savedBatch);
    }

    /**
     * Orchestrates end-to-end bulk resume upload and processing.
     *
     * @param files list of uploaded multipart files (up to 100)
     * @param batchName optional user-provided batch label
     * @param jobDescriptionId optional target job ID for auto-screening
     * @return composite batch outcome DTO
     */
    public ResumeBatchDetailResponseDto processBatch(List<MultipartFile> files, String batchName, Long jobDescriptionId) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Cannot process an empty or null file batch.");
        }

        final JobDescription targetJob = (jobDescriptionId != null)
                ? jobDescriptionRepository.findById(jobDescriptionId)
                        .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobDescriptionId))
                : null;
        if (targetJob != null && securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageJob(targetJob);
        }

        final String effectiveBatchName = (batchName != null && !batchName.isBlank())
                ? batchName.trim()
                : "Batch_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // Step 1: Create ResumeBatch and initial ResumeBatchFile records in a dedicated short transaction
        final Long batchId = transactionTemplate.execute(status -> {
            ResumeBatch batch = new ResumeBatch(effectiveBatchName, files.size(), 0, "PROCESSING");
            batch.setJobDescription(targetJob);
            if (securityAuthorizationService != null) {
                batch.setUploadedBy(securityAuthorizationService.getCurrentUser());
            }
            ResumeBatch savedBatch = resumeBatchRepository.save(batch);

            for (MultipartFile file : files) {
                String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
                ResumeBatchFile batchFile = new ResumeBatchFile(savedBatch, originalFilename, null, null, "PENDING");
                resumeBatchFileRepository.save(batchFile);
            }
            return savedBatch.getId();
        });

        logger.info("Initialized batch ID {} with {} files for processing.", batchId, files.size());

        // Step 2: Retrieve the list of pending batch file entities to iterate over
        List<ResumeBatchFile> batchFiles = resumeBatchFileRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        Set<String> seenHashesInBatch = new HashSet<>();

        // Step 3: Process each file independently with failure isolation
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            ResumeBatchFile batchFile = (i < batchFiles.size()) ? batchFiles.get(i) : null;
            if (batchFile == null) {
                continue;
            }

            processSingleBatchFile(batchId, batchFile.getId(), file, jobDescriptionId, seenHashesInBatch);
        }

        // Step 4: Finalize overall batch status based on individual outcomes
        transactionTemplate.executeWithoutResult(status -> {
            ResumeBatch batch = resumeBatchRepository.findById(batchId)
                    .orElseThrow(() -> new IllegalStateException("Batch not found with ID: " + batchId));

            long failedCount = resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "FAILED");
            long parsedCount = resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PARSED");
            long duplicateCount = resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "DUPLICATE");
            long total = batch.getTotalFiles();

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
            logger.info("Batch ID {} completed with status: {} (parsed={}, duplicate={}, failed={})",
                    batchId, finalStatus, parsedCount, duplicateCount, failedCount);
        });

        return getBatchDetailDto(batchId);
    }

    /**
     * Processes a single file within the batch in complete isolation.
     * Any failure updates only this file's status to FAILED, increments the batch processed counter,
     * and guarantees the remaining files continue uninterrupted.
     */
    private void processSingleBatchFile(
            Long batchId,
            Long batchFileId,
            MultipartFile file,
            Long jobDescriptionId,
            Set<String> seenHashesInBatch
    ) {
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
        String fileType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        long fileSize = file.getSize();

        try {
            // A. Read bytes and compute SHA-256
            byte[] fileBytes;
            try {
                fileBytes = file.getBytes();
            } catch (IOException e) {
                recordBatchFileFailure(batchId, batchFileId, null, null, "Failed to read file bytes: " + e.getMessage());
                return;
            }

            String fileHash = fileStorageService.computeSha256(fileBytes);

            // B. Check for duplicate hash in database or within the same upload batch
            if (resumeRepository.existsByFileHash(fileHash) || seenHashesInBatch.contains(fileHash)) {
                logger.warn("Duplicate resume content detected for file '{}' (hash: {})", originalFilename, fileHash);
                recordBatchFileDuplicate(batchId, batchFileId, fileHash);
                return;
            }
            seenHashesInBatch.add(fileHash);

            // C. Store file to physical storage
            String storedPath;
            try {
                storedPath = fileStorageService.storeFile(file);
            } catch (Exception e) {
                recordBatchFileFailure(batchId, batchFileId, fileHash, null, "Failed to store file on disk: " + e.getMessage());
                return;
            }

            // D. Call AI microservice to parse document text and extract skills
            ResumeParseResponse parseResponse;
            try {
                parseResponse = aiServiceClient.parseDocument(fileBytes, originalFilename);
            } catch (Exception e) {
                fileStorageService.deleteFile(storedPath);
                recordBatchFileFailure(batchId, batchFileId, fileHash, storedPath, "AI service error: " + e.getMessage());
                return;
            }

            if (parseResponse == null || !parseResponse.success()) {
                String errorMsg = (parseResponse != null && parseResponse.error_message() != null)
                        ? parseResponse.error_message()
                        : "AI document parsing failed.";
                fileStorageService.deleteFile(storedPath);
                recordBatchFileFailure(batchId, batchFileId, fileHash, storedPath, errorMsg);
                return;
            }

            // E. Candidate resolution: extract email/name from parsed text or generate fallback profile
            final ResumeParseResponse finalResponse = parseResponse;
            final String finalStoredPath = storedPath;
            final String finalFileHash = fileHash;
            final Long[] savedResumeIdHolder = new Long[1];

            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Candidate candidate = resolveCandidateFromResume(finalResponse, originalFilename, finalFileHash);

                    Resume resume = new Resume(
                            candidate,
                            originalFilename,
                            finalStoredPath,
                            finalFileHash,
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

                    // Mark batch file as PARSED
                    ResumeBatchFile managedBatchFile = resumeBatchFileRepository.findById(batchFileId)
                            .orElseThrow(() -> new IllegalStateException("Batch file not found: " + batchFileId));
                    managedBatchFile.setFileHash(finalFileHash);
                    managedBatchFile.setFilePath(finalStoredPath);
                    managedBatchFile.setStatus("PARSED");
                    managedBatchFile.setErrorMessage(null);
                    resumeBatchFileRepository.save(managedBatchFile);

                    // Increment processed count on batch
                    incrementBatchProcessedCount(batchId);
                });

                logger.info("Successfully persisted and parsed batch file ID {} ('{}')", batchFileId, originalFilename);

            } catch (Exception ex) {
                handlePersistenceException(batchId, batchFileId, originalFilename, finalFileHash, finalStoredPath, ex);
                return;
            }

            // Auto-screening executed completely OUTSIDE and AFTER the persistence transaction commit.
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
            recordBatchFileFailure(batchId, batchFileId, null, null, "Processing failure: " + ex.getMessage());
        }
    }

    /**
     * Handles persistence exceptions with database-verified file cleanup.
     * Differentiates between:
     * 1. Committed: Record exists with the same stored file path -> Preserve physical file.
     * 2. Duplicate: Record exists with different file path -> Delete newly uploaded duplicate file, record DUPLICATE.
     * 3. Uncertain: Database unreachable or query fails -> Preserve physical file, defer cleanup, record FAILED.
     * 4. Rolled back: Database reachable, no record exists -> Delete orphaned physical file, record FAILED.
     */
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
            logger.warn("Database query failed while verifying persistence outcome for file '{}' (hash: {}): {}",
                    originalFilename, fileHash, queryEx.getMessage());
            dbReachable = false;
        }

        if (dbReachable && existingRecordOpt.isPresent()) {
            Resume existingRecord = existingRecordOpt.get();
            String existingPath = existingRecord.getFilePath();

            if (storedPath != null && storedPath.equals(existingPath)) {
                // Outcome 1: COMMITTED - Database transaction actually committed before error returned
                logger.warn("Resume record for hash {} confirmed committed with path '{}'. Preserving physical file despite error: {}",
                        fileHash, storedPath, originalEx.getMessage());
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        resumeBatchFileRepository.findById(batchFileId).ifPresent(batchFile -> {
                            if (!"PARSED".equals(batchFile.getStatus())) {
                                batchFile.setFileHash(fileHash);
                                batchFile.setFilePath(storedPath);
                                batchFile.setStatus("PARSED");
                                batchFile.setErrorMessage(null);
                                resumeBatchFileRepository.save(batchFile);
                                incrementBatchProcessedCount(batchId);
                            }
                        });
                    });
                } catch (Exception updateEx) {
                    logger.error("Failed to update batch file ID {} status to PARSED after confirmed commit: {}",
                            batchFileId, updateEx.getMessage(), updateEx);
                }
            } else {
                // Outcome 2: DUPLICATE - A different resume record already exists with this hash
                logger.warn("Concurrent duplicate resume hash detected during insert for file '{}' (hash: {}). Deleting newly uploaded file copy: {}",
                        originalFilename, fileHash, originalEx.getMessage());
                try {
                    fileStorageService.deleteFile(storedPath);
                } catch (Exception deleteEx) {
                    logger.warn("Failed to delete duplicate physical file '{}': {}", storedPath, deleteEx.getMessage());
                }
                recordBatchFileDuplicate(batchId, batchFileId, fileHash);
            }

        } else if (!dbReachable) {
            // Outcome 3: UNCERTAIN - Database is unreachable; cannot confirm whether record committed
            logger.warn("Database outcome uncertain for file '{}' (hash: {}, path: '{}'). Deferring physical file cleanup. Cause: {}",
                    originalFilename, fileHash, storedPath, originalEx.getMessage());
            recordBatchFileFailure(batchId, batchFileId, fileHash, storedPath,
                    "Database outcome uncertain: " + originalEx.getMessage());

        } else {
            // Outcome 4: ROLLED BACK - Database reachable and confirms no record exists for this hash
            logger.error("Database transaction confirmed rolled back for batch file ID {} ('{}'). Deleting orphaned physical file. Cause: {}",
                    batchFileId, originalFilename, originalEx.getMessage(), originalEx);
            try {
                fileStorageService.deleteFile(storedPath);
            } catch (Exception deleteEx) {
                logger.warn("Failed to delete orphaned physical file '{}': {}", storedPath, deleteEx.getMessage());
            }
            recordBatchFileFailure(batchId, batchFileId, fileHash, null,
                    "Database persistence failure: " + originalEx.getMessage());
        }
    }

    /**
     * Resolves an existing candidate by email or creates a new profile using
     * intelligent contact extraction (name, email, phone) with deterministic fallbacks.
     */
    private Candidate resolveCandidateFromResume(ResumeParseResponse parseResponse, String filename, String fileHash) {
        // 1. Resolve Email
        String email = (parseResponse != null && parseResponse.candidate_email() != null && !parseResponse.candidate_email().isBlank())
                ? parseResponse.candidate_email().trim().toLowerCase()
                : null;

        // Fallback: search parsed text directly with regex if AI missed it
        if (email == null && parseResponse != null && parseResponse.parsed_text() != null && !parseResponse.parsed_text().isBlank()) {
            Matcher matcher = EMAIL_PATTERN.matcher(parseResponse.parsed_text());
            if (matcher.find()) {
                email = matcher.group().toLowerCase().trim();
            }
        }

        // Ultimate deterministic fallback if no email exists on the resume
        if (email == null || email.isBlank()) {
            String hashPrefix = (fileHash != null && fileHash.length() >= 12) ? fileHash.substring(0, 12) : "auto";
            email = "candidate_" + hashPrefix + "@batch.local";
        }

        // 2. Resolve Name
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

        // Fallback: derive name from filename if AI name is not available
        if (firstName == null || firstName.isBlank()) {
            String cleanName = filename.replaceAll("(?i)\\.(pdf|docx|txt)$", "")
                    .replaceAll("[_-]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            String[] nameParts = cleanName.split("\\s+");
            firstName = nameParts.length > 0 && !nameParts[0].isBlank() ? nameParts[0] : "Candidate";
            lastName = nameParts.length > 1 ? String.join(" ", Arrays.copyOfRange(nameParts, 1, nameParts.length)) : "Profile";
        }

        // 3. Resolve Phone
        String phone = (parseResponse != null && parseResponse.candidate_phone() != null && !parseResponse.candidate_phone().isBlank())
                ? parseResponse.candidate_phone().trim()
                : null;

        return candidateService.getOrCreateCandidate(email, firstName, lastName, phone);
    }

    /**
     * Safely records a file failure within its own transaction and increments the batch progress.
     */
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

    /**
     * Records a duplicate file within its own transaction and increments the batch progress.
     */
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

    /**
     * Atomically increments the processed file count on the parent batch.
     */
    private void incrementBatchProcessedCount(Long batchId) {
        resumeBatchRepository.findById(batchId).ifPresent(batch -> {
            batch.setProcessedFiles(batch.getProcessedFiles() + 1);
            resumeBatchRepository.save(batch);
        });
    }

    // =========================================================================
    // Query & Projection Methods
    // =========================================================================

    public ResumeBatch getBatchById(Long batchId) {
        ResumeBatch batch = resumeBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Resume batch not found with ID: " + batchId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewBatch(batch);
        }
        return batch;
    }

    public ResumeBatchResponseDto getBatchDto(Long batchId) {
        return ResumeBatchResponseDto.fromEntity(getBatchById(batchId));
    }

    public List<ResumeBatchFileResponseDto> getBatchFilesDto(Long batchId) {
        ResumeBatch batch = getBatchById(batchId);
        return resumeBatchFileRepository.findByBatchIdOrderByCreatedAtAsc(batchId)
                .stream()
                .map(ResumeBatchFileResponseDto::fromEntity)
                .toList();
    }

    public ResumeBatchDetailResponseDto getBatchDetailDto(Long batchId) {
        ResumeBatch batch = getBatchById(batchId);
        List<ResumeBatchFile> files = resumeBatchFileRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        return ResumeBatchDetailResponseDto.of(batch, files);
    }

    public List<ResumeBatchResponseDto> getAllBatchesDto() {
        if (securityAuthorizationService != null) {
            if (securityAuthorizationService.isRecruiter()) {
                Long recruiterId = securityAuthorizationService.getCurrentUserId();
                return resumeBatchRepository.findByUploadedByIdOrderByCreatedAtDesc(recruiterId)
                        .stream()
                        .map(ResumeBatchResponseDto::fromEntity)
                        .toList();
            } else if (securityAuthorizationService.isHiringManager()) {
                throw new org.springframework.security.access.AccessDeniedException("Access denied: Hiring managers cannot view resume batches");
            }
        }
        return resumeBatchRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(ResumeBatchResponseDto::fromEntity)
                .toList();
    }
}


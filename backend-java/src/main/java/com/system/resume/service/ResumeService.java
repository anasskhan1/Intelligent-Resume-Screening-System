package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.client.AiServiceClient.ExtractedSkill;
import com.system.resume.client.AiServiceClient.ResumeParseResponse;
import com.system.resume.dto.ResumeResponseDto;
import com.system.resume.dto.ResumeSkillDto;
import com.system.resume.model.Candidate;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeSkill;
import com.system.resume.repository.CandidateRepository;
import com.system.resume.repository.ResumeRepository;
import com.system.resume.repository.ResumeSkillRepository;
import com.system.resume.security.SecurityAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service orchestrating resume ingestion, physical storage, NLP-based text/skill
 * extraction via the Python AI microservice, database persistence, and in-transaction
 * DTO projection.
 *
 * Adheres strictly to transaction isolation:
 * - Pre-validates candidate existence before disk writes.
 * - Keeps physical file storage and HTTP calls to the AI microservice outside database transactions.
 * - Reloads freshly managed entities inside write transactions.
 * - Reliably updates processing statuses to PENDING, PARSED, or FAILED.
 * - Performs all DTO mappings inside active transactional contexts to prevent lazy-loading exceptions.
 */
@Service
public class ResumeService {

    private static final Logger logger = LoggerFactory.getLogger(ResumeService.class);

    private final ResumeRepository resumeRepository;
    private final ResumeSkillRepository resumeSkillRepository;
    private final CandidateRepository candidateRepository;
    private final FileStorageService fileStorageService;
    private final AiServiceClient aiServiceClient;
    private final TransactionTemplate transactionTemplate;
    private final SecurityAuthorizationService securityAuthorizationService;

    @Autowired
    public ResumeService(
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            CandidateRepository candidateRepository,
            FileStorageService fileStorageService,
            AiServiceClient aiServiceClient,
            PlatformTransactionManager transactionManager,
            SecurityAuthorizationService securityAuthorizationService
    ) {
        this.resumeRepository = resumeRepository;
        this.resumeSkillRepository = resumeSkillRepository;
        this.candidateRepository = candidateRepository;
        this.fileStorageService = fileStorageService;
        this.aiServiceClient = aiServiceClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.securityAuthorizationService = securityAuthorizationService;
    }

    public ResumeService(
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            CandidateRepository candidateRepository,
            FileStorageService fileStorageService,
            AiServiceClient aiServiceClient,
            PlatformTransactionManager transactionManager
    ) {
        this(resumeRepository, resumeSkillRepository, candidateRepository,
             fileStorageService, aiServiceClient, transactionManager, null);
    }

    /**
     * Complete resume upload pipeline:
     * 1. Pre-validates candidate existence (outside transaction).
     * 2. Saves file to disk (outside transaction).
     * 3. Creates Resume entity in DB with status PENDING (inside short write transaction).
     * 4. Calls Python AI service to parse text and extract skills (outside transaction).
     * 5. Updates status to PARSED and persists skills on success, or FAILED on error (inside write transaction).
     *
     * @param candidateId ID of candidate owning the resume
     * @param file the uploaded multipart file
     * @return the processed Resume entity
     */
    public Resume uploadAndProcessResume(Long candidateId, MultipartFile file) {
        if (candidateId == null) {
            throw new IllegalArgumentException("Candidate ID cannot be null.");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file cannot be null or empty.");
        }

        // 1. Verify candidate exists before any file I/O
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found with ID: " + candidateId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageCandidate(candidate);
        }

        // Read file bytes and metadata before transaction
        final byte[] fileBytes;
        final String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "resume");
        final long fileSize = file.getSize();
        final String fileType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            logger.error("Failed to read file bytes for '{}': {}", originalFilename, e.getMessage(), e);
            throw new RuntimeException("Failed to read uploaded file: " + originalFilename, e);
        }

        // 2. Physical storage on disk (outside DB transaction)
        String storedFilePath = fileStorageService.storeFile(file);

        // 3. Register initial Resume entity in DB with status PENDING
        final Long resumeId;
        try {
            resumeId = transactionTemplate.execute(status -> {
                // Reload managed Candidate entity in current persistence context
                Candidate managedCandidate = candidateRepository.findById(candidateId)
                        .orElseThrow(() -> new IllegalArgumentException("Candidate not found with ID: " + candidateId));

                Resume resume = new Resume(
                        managedCandidate,
                        originalFilename,
                        storedFilePath,
                        fileType,
                        fileSize,
                        null,
                        "PENDING"
                );

                return resumeRepository.save(resume).getId();
            });
        } catch (Exception e) {
            // Clean up file if database insertion fails
            fileStorageService.deleteFile(storedFilePath);
            logger.error("Failed to register initial resume record in DB; cleaned up file at '{}'", storedFilePath, e);
            throw e;
        }

        // 4. External AI parsing call over HTTP (outside DB transaction)
        ResumeParseResponse parseResponse = null;
        boolean parsingSucceeded = false;
        try {
            logger.info("Calling AI service to parse resume ID {} (file: {})", resumeId, originalFilename);
            parseResponse = aiServiceClient.parseDocument(fileBytes, originalFilename);
            parsingSucceeded = (parseResponse != null && parseResponse.success());
        } catch (Exception ex) {
            logger.error("AI service failure while parsing resume ID {}: {}", resumeId, ex.getMessage(), ex);
            parsingSucceeded = false;
        }

        // 5. Update status and persist results in a short transaction
        if (parsingSucceeded) {
            final ResumeParseResponse finalResponse = parseResponse;
            transactionTemplate.executeWithoutResult(status -> {
                Resume managedResume = resumeRepository.findById(resumeId)
                        .orElseThrow(() -> new IllegalStateException("Resume not found with ID: " + resumeId));

                managedResume.setParsedText(finalResponse.parsed_text());
                managedResume.setProcessingStatus("PARSED");
                resumeRepository.save(managedResume);

                if (finalResponse.extracted_skills() != null && !finalResponse.extracted_skills().isEmpty()) {
                    List<ResumeSkill> skills = finalResponse.extracted_skills().stream()
                            .map(s -> new ResumeSkill(
                                    managedResume,
                                    s.skill_name(),
                                    s.category(),
                                    s.confidence_score() != null ? BigDecimal.valueOf(s.confidence_score()) : BigDecimal.ZERO
                            ))
                            .toList();
                    resumeSkillRepository.saveAll(skills);
                }
            });

            logger.info("Successfully processed and parsed resume ID: {}", resumeId);
            return getResumeById(resumeId);
        } else {
            // Failure branch: mark status as FAILED
            transactionTemplate.executeWithoutResult(status -> {
                Resume managedResume = resumeRepository.findById(resumeId)
                        .orElseThrow(() -> new IllegalStateException("Resume not found with ID: " + resumeId));
                managedResume.setProcessingStatus("FAILED");
                resumeRepository.save(managedResume);
            });

            String errorMsg = (parseResponse != null && parseResponse.error_message() != null)
                    ? parseResponse.error_message()
                    : "AI document parsing failed or timed out.";
            logger.warn("Resume ID {} marked as FAILED: {}", resumeId, errorMsg);
            throw new RuntimeException("Resume parsing failed: " + errorMsg);
        }
    }

    /**
     * DTO-facing upload and processing method that returns fully mapped DTO.
     */
    public ResumeResponseDto uploadAndProcessResumeDto(Long candidateId, MultipartFile file) {
        Resume processed = uploadAndProcessResume(candidateId, file);
        return getResumeDtoById(processed.getId());
    }

    /**
     * Ingests and processes raw resume text content for a candidate.
     */
    public Resume processRawTextResume(Long candidateId, String text, String title) {
        if (candidateId == null) {
            throw new IllegalArgumentException("Candidate ID cannot be null.");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Resume text cannot be blank.");
        }

        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found with ID: " + candidateId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageCandidate(candidate);
        }

        final String safeTitle = (title != null && !title.isBlank()) ? title.trim() : "raw_resume.txt";

        // Register initial Resume entity in DB with status PENDING
        final Long resumeId = transactionTemplate.execute(status -> {
            Candidate managedCandidate = candidateRepository.findById(candidateId)
                    .orElseThrow(() -> new IllegalArgumentException("Candidate not found with ID: " + candidateId));

            Resume resume = new Resume(
                    managedCandidate,
                    safeTitle,
                    "memory://raw_text",
                    "text/plain",
                    (long) text.getBytes().length,
                    null,
                    "PENDING"
            );

            return resumeRepository.save(resume).getId();
        });

        // External AI call over HTTP (outside DB transaction)
        ResumeParseResponse parseResponse = null;
        boolean parsingSucceeded = false;
        try {
            logger.info("Calling AI service to parse raw text for resume ID: {}", resumeId);
            parseResponse = aiServiceClient.parseText(text, safeTitle);
            parsingSucceeded = (parseResponse != null && parseResponse.success());
        } catch (Exception ex) {
            logger.error("AI service error parsing raw text for resume ID {}: {}", resumeId, ex.getMessage(), ex);
            parsingSucceeded = false;
        }

        // Finalize status and persist skills
        if (parsingSucceeded) {
            final ResumeParseResponse finalResponse = parseResponse;
            transactionTemplate.executeWithoutResult(status -> {
                Resume managedResume = resumeRepository.findById(resumeId)
                        .orElseThrow(() -> new IllegalStateException("Resume not found with ID: " + resumeId));

                managedResume.setParsedText(finalResponse.parsed_text());
                managedResume.setProcessingStatus("PARSED");
                resumeRepository.save(managedResume);

                if (finalResponse.extracted_skills() != null && !finalResponse.extracted_skills().isEmpty()) {
                    List<ResumeSkill> skills = finalResponse.extracted_skills().stream()
                            .map(s -> new ResumeSkill(
                                    managedResume,
                                    s.skill_name(),
                                    s.category(),
                                    s.confidence_score() != null ? BigDecimal.valueOf(s.confidence_score()) : BigDecimal.ZERO
                            ))
                            .toList();
                    resumeSkillRepository.saveAll(skills);
                }
            });

            logger.info("Successfully processed raw text resume ID: {}", resumeId);
            return getResumeById(resumeId);
        } else {
            transactionTemplate.executeWithoutResult(status -> {
                Resume managedResume = resumeRepository.findById(resumeId)
                        .orElseThrow(() -> new IllegalStateException("Resume not found with ID: " + resumeId));
                managedResume.setProcessingStatus("FAILED");
                resumeRepository.save(managedResume);
            });

            String errorMsg = (parseResponse != null && parseResponse.error_message() != null)
                    ? parseResponse.error_message()
                    : "AI text parsing failed or timed out.";
            logger.warn("Resume ID {} marked as FAILED: {}", resumeId, errorMsg);
            throw new RuntimeException("Resume text parsing failed: " + errorMsg);
        }
    }

    /**
     * DTO-facing raw text processing method.
     */
    public ResumeResponseDto processRawTextResumeDto(Long candidateId, String text, String title) {
        Resume processed = processRawTextResume(candidateId, text, title);
        return getResumeDtoById(processed.getId());
    }

    /**
     * Stateless ad-hoc document parsing (delegates directly to AI service without database persistence).
     */
    public ResumeParseResponse parseDocumentAdHoc(byte[] fileBytes, String filename) {
        return aiServiceClient.parseDocument(fileBytes, filename);
    }

    /**
     * Stateless ad-hoc text parsing (delegates directly to AI service without database persistence).
     */
    public ResumeParseResponse parseTextAdHoc(String text, String filename) {
        return aiServiceClient.parseText(text, filename);
    }

    /**
     * Retrieves resume entity by primary key.
     */
    @Transactional(readOnly = true)
    public Resume getResumeById(Long id) {
        Resume resume = resumeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Resume not found with ID: " + id));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewResume(resume);
        }
        return resume;
    }

    /**
     * DTO-facing method for retrieving resume details by ID with skills mapped in-transaction.
     */
    @Transactional(readOnly = true)
    public ResumeResponseDto getResumeDtoById(Long id) {
        Resume resume = getResumeById(id);
        List<ResumeSkill> skills = resumeSkillRepository.findByResumeId(id);
        return ResumeResponseDto.fromEntity(resume, skills);
    }

    /**
     * Retrieves all resume entities uploaded for a candidate.
     */
    @Transactional(readOnly = true)
    public List<Resume> getResumesByCandidateId(Long candidateId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found with ID: " + candidateId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewCandidate(candidate);
        }
        return resumeRepository.findByCandidateIdOrderByUploadedAtDesc(candidateId);
    }

    /**
     * DTO-facing method for retrieving all resumes for a candidate.
     */
    @Transactional(readOnly = true)
    public List<ResumeResponseDto> getResumeDtosByCandidateId(Long candidateId) {
        List<Resume> resumes = getResumesByCandidateId(candidateId);
        return resumes.stream()
                .map(r -> {
                    List<ResumeSkill> skills = resumeSkillRepository.findByResumeId(r.getId());
                    return ResumeResponseDto.fromEntity(r, skills);
                })
                .toList();
    }

    /**
     * Retrieves the latest resume uploaded for a candidate.
     */
    @Transactional(readOnly = true)
    public Optional<Resume> getLatestResumeByCandidateId(Long candidateId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found with ID: " + candidateId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewCandidate(candidate);
        }
        return resumeRepository.findTopByCandidateIdOrderByUploadedAtDesc(candidateId);
    }

    /**
     * Retrieves all extracted skills for a given resume entity.
     */
    @Transactional(readOnly = true)
    public List<ResumeSkill> getSkillsByResumeId(Long resumeId) {
        // Enforces view permission via getResumeById
        getResumeById(resumeId);
        return resumeSkillRepository.findByResumeId(resumeId);
    }

    /**
     * DTO-facing method for retrieving extracted skills.
     */
    @Transactional(readOnly = true)
    public List<ResumeSkillDto> getSkillDtosByResumeId(Long resumeId) {
        return getSkillsByResumeId(resumeId).stream()
                .map(ResumeSkillDto::fromEntity)
                .toList();
    }

    /**
     * Deletes a resume entity, cascading to extracted skills and deleting the stored file from disk.
     */
    @Transactional
    public void deleteResume(Long id) {
        Resume resume = getResumeById(id);
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageResume(resume);
        }
        String filePath = resume.getFilePath();

        resumeRepository.deleteById(id);

        if (filePath != null && !filePath.startsWith("memory://")) {
            fileStorageService.deleteFile(filePath);
        }
        logger.info("Deleted resume ID: {}", id);
    }
}

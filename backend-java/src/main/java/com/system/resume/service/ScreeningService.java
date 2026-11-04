package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.client.AiServiceClient.MatchRequest;
import com.system.resume.client.AiServiceClient.MatchResponse;
import com.system.resume.dto.ScreeningResponseDto;
import com.system.resume.model.Candidate;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeBatch;
import com.system.resume.model.ResumeSkill;
import com.system.resume.model.ScreeningResult;
import com.system.resume.repository.JobDescriptionRepository;
import com.system.resume.repository.ResumeBatchRepository;
import com.system.resume.repository.ResumeRepository;
import com.system.resume.repository.ResumeSkillRepository;
import com.system.resume.repository.ScreeningResultRepository;
import com.system.resume.security.SecurityAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Service managing candidate resume screening against target job descriptions.
 * Computes semantic matching, skill match/gap analysis, and experience scoring
 * via the Python AI microservice, persisting structured screening evaluations
 * and projecting them safely to DTOs inside active transactions.
 *
 * Adheres strictly to decoupled transaction boundaries:
 * - Read phase extracts unmanaged data records before external calls.
 * - AI HTTP calls occur outside any database transaction.
 * - Managed entities are reloaded within the write transaction before persistence.
 * - DTO projection occurs inside the active session, preventing LazyInitializationException.
 */
@Service
public class ScreeningService {

    private static final Logger logger = LoggerFactory.getLogger(ScreeningService.class);

    private final ScreeningResultRepository screeningResultRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeSkillRepository resumeSkillRepository;
    private final AiServiceClient aiServiceClient;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final SecurityAuthorizationService securityAuthorizationService;
    private final ResumeBatchRepository resumeBatchRepository;

    @Autowired
    public ScreeningService(
            ScreeningResultRepository screeningResultRepository,
            JobDescriptionRepository jobDescriptionRepository,
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            AiServiceClient aiServiceClient,
            PlatformTransactionManager transactionManager,
            SecurityAuthorizationService securityAuthorizationService,
            ResumeBatchRepository resumeBatchRepository
    ) {
        this.screeningResultRepository = screeningResultRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.resumeRepository = resumeRepository;
        this.resumeSkillRepository = resumeSkillRepository;
        this.aiServiceClient = aiServiceClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.securityAuthorizationService = securityAuthorizationService;
        this.resumeBatchRepository = resumeBatchRepository;
    }

    public ScreeningService(
            ScreeningResultRepository screeningResultRepository,
            JobDescriptionRepository jobDescriptionRepository,
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            AiServiceClient aiServiceClient,
            PlatformTransactionManager transactionManager,
            SecurityAuthorizationService securityAuthorizationService
    ) {
        this(screeningResultRepository, jobDescriptionRepository, resumeRepository,
                resumeSkillRepository, aiServiceClient, transactionManager, securityAuthorizationService, null);
    }

    public ScreeningService(
            ScreeningResultRepository screeningResultRepository,
            JobDescriptionRepository jobDescriptionRepository,
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            AiServiceClient aiServiceClient,
            PlatformTransactionManager transactionManager
    ) {
        this(screeningResultRepository, jobDescriptionRepository, resumeRepository,
                resumeSkillRepository, aiServiceClient, transactionManager, null, null);
    }

    /**
     * Screens a candidate's resume against a specific job description.
     * Enforces user authorization (ADMIN or owning RECRUITER).
     *
     * 1. Reads job and resume data and projects to MatchRequest (read-only transaction).
     * 2. Calls Python AI service evaluateMatch endpoint over HTTP (outside transaction).
     * 3. Reloads managed entities and persists/updates ScreeningResult (write transaction).
     *
     * @param jobId target job description ID
     * @param resumeId candidate resume ID
     * @return persisted ScreeningResult entity
     */
    public ScreeningResult screenCandidateResume(Long jobId, Long resumeId) {
        return executeScreening(null, jobId, resumeId, true);
    }

    /**
     * Dedicated internal screening method for asynchronous batch ingestion workers.
     * Used exclusively when the batch and target job ownership have already been verified
     * during authenticated HTTP batch intake.
     * Verifies that the batch is associated with the requested target job before screening.
     * Does not require or inspect SecurityContextHolder on worker threads.
     *
     * @param batchId pre-authorized batch identifier
     * @param jobId target job description ID
     * @param resumeId candidate resume ID
     * @return persisted ScreeningResult entity
     */
    public ScreeningResult screenCandidateResumeForBatch(Long batchId, Long jobId, Long resumeId) {
        if (batchId == null) {
            throw new IllegalArgumentException("Batch ID cannot be null for batch auto-screening.");
        }
        return executeScreening(batchId, jobId, resumeId, false);
    }

    private ScreeningResult executeScreening(Long batchId, Long jobId, Long resumeId, boolean enforceAuthorization) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null.");
        }
        if (resumeId == null) {
            throw new IllegalArgumentException("Resume ID cannot be null.");
        }

        // Phase 1: Read and project required data to an immutable MatchRequest
        MatchRequest matchRequest = readOnlyTransactionTemplate.execute(status -> {
            JobDescription job = jobDescriptionRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));

            if (enforceAuthorization) {
                if (securityAuthorizationService != null) {
                    securityAuthorizationService.validateCanPerformScreening(job);
                }
            } else {
                // Background worker validation: verify the batch is authorized for this target job
                if (batchId != null && resumeBatchRepository != null) {
                    ResumeBatch batch = resumeBatchRepository.findById(batchId)
                            .orElseThrow(() -> new IllegalArgumentException("Batch not found with ID: " + batchId));
                    if (batch.getJobDescription() == null || !job.getId().equals(batch.getJobDescription().getId())) {
                        throw new IllegalStateException("Security violation: Batch ID " + batchId
                                + " is not authorized for target Job ID " + jobId);
                    }
                }
            }

            Resume resume = resumeRepository.findById(resumeId)
                    .orElseThrow(() -> new IllegalArgumentException("Resume not found with ID: " + resumeId));

            if (resume.getParsedText() == null || resume.getParsedText().isBlank()) {
                throw new IllegalStateException("Resume ID " + resumeId + " has no parsed text available for screening.");
            }

            Candidate candidate = resume.getCandidate();
            String candidateName = (candidate != null)
                    ? (candidate.getFirstName() + " " + candidate.getLastName()).trim()
                    : "Candidate #" + resumeId;

            List<ResumeSkill> skills = resumeSkillRepository.findByResumeId(resumeId);
            List<String> skillNames = skills.stream()
                    .map(ResumeSkill::getSkillName)
                    .toList();

            return new MatchRequest(
                    job.getId(),
                    job.getTitle(),
                    job.getDescriptionText(),
                    job.getRequiredSkills() != null ? job.getRequiredSkills() : Collections.emptyList(),
                    job.getMinExperienceYears() != null ? job.getMinExperienceYears() : 0,
                    resume.getId(),
                    candidateName,
                    resume.getParsedText(),
                    skillNames
            );
        });

        // Phase 2: Call Python AI Microservice over HTTP (completely outside DB transaction)
        logger.info("Evaluating AI match for Job ID {} and Resume ID {}", jobId, resumeId);
        MatchResponse response = aiServiceClient.evaluateMatch(matchRequest);
        if (response == null) {
            throw new RuntimeException("AI match evaluation returned null response for Job ID " + jobId + " and Resume ID " + resumeId);
        }

        // Phase 3: Reload managed entities inside an atomic write transaction and persist result
        BigDecimal matchScore = response.match_score() != null ? BigDecimal.valueOf(response.match_score()) : BigDecimal.ZERO;
        BigDecimal skillsScore = response.skills_score() != null ? BigDecimal.valueOf(response.skills_score()) : BigDecimal.ZERO;
        BigDecimal experienceScore = response.experience_score() != null ? BigDecimal.valueOf(response.experience_score()) : BigDecimal.ZERO;
        List<String> matchedSkills = response.matched_skills() != null ? response.matched_skills() : new ArrayList<>();
        List<String> missingSkills = response.missing_skills() != null ? response.missing_skills() : new ArrayList<>();
        String aiSummary = response.ai_summary();

        try {
            return transactionTemplate.execute(status -> {
                JobDescription managedJob = jobDescriptionRepository.findById(jobId)
                        .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));

                Resume managedResume = resumeRepository.findById(resumeId)
                        .orElseThrow(() -> new IllegalArgumentException("Resume not found with ID: " + resumeId));

                // Check if a screening result already exists (satisfies unique constraint uk_job_resume_screening)
                Optional<ScreeningResult> existingOpt = screeningResultRepository.findByJobDescriptionIdAndResumeId(jobId, resumeId);
                ScreeningResult screeningResult;

                if (existingOpt.isPresent()) {
                    screeningResult = existingOpt.get();
                    screeningResult.setMatchScore(matchScore);
                    screeningResult.setSkillsScore(skillsScore);
                    screeningResult.setExperienceScore(experienceScore);
                    screeningResult.setMatchedSkills(matchedSkills);
                    screeningResult.setMissingSkills(missingSkills);
                    screeningResult.setAiSummary(aiSummary);
                    screeningResult.setScreeningStatus("COMPLETED");
                } else {
                    screeningResult = new ScreeningResult(
                            managedJob,
                            managedResume,
                            matchScore,
                            skillsScore,
                            experienceScore,
                            matchedSkills,
                            missingSkills,
                            aiSummary,
                            "COMPLETED"
                    );
                }

                ScreeningResult saved = screeningResultRepository.save(screeningResult);
                logger.info("Persisted screening result ID: {} for Job ID {} and Resume ID {}", saved.getId(), jobId, resumeId);
                return saved;
            });
        } catch (DataIntegrityViolationException dive) {
            logger.warn("Concurrent screening result insert collision for Job ID {} and Resume ID {}; updating existing record", jobId, resumeId);
            return transactionTemplate.execute(status -> {
                ScreeningResult existing = screeningResultRepository.findByJobDescriptionIdAndResumeId(jobId, resumeId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Screening result should exist after collision for Job ID " + jobId + " and Resume ID " + resumeId, dive));
                existing.setMatchScore(matchScore);
                existing.setSkillsScore(skillsScore);
                existing.setExperienceScore(experienceScore);
                existing.setMatchedSkills(matchedSkills);
                existing.setMissingSkills(missingSkills);
                existing.setAiSummary(aiSummary);
                existing.setScreeningStatus("COMPLETED");
                ScreeningResult saved = screeningResultRepository.save(existing);
                logger.info("Updated existing screening result ID: {} following concurrency resolution for Job ID {} and Resume ID {}",
                        saved.getId(), jobId, resumeId);
                return saved;
            });
        }
    }

    /**
     * DTO-facing screening method with in-transaction mapping to prevent lazy loading errors.
     */
    public ScreeningResponseDto screenCandidateResumeDto(Long jobId, Long resumeId) {
        ScreeningResult result = screenCandidateResume(jobId, resumeId);
        return getScreeningResultDtoById(result.getId());
    }

    /**
     * Stateless ad-hoc candidate screening without database persistence.
     */
    public MatchResponse screenAdHoc(MatchRequest request) {
        return aiServiceClient.evaluateMatch(request);
    }

    /**
     * Retrieves screening result entity by ID.
     */
    @Transactional(readOnly = true)
    public ScreeningResult getScreeningResultById(Long id) {
        ScreeningResult result = screeningResultRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Screening result not found with ID: " + id));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewScreeningResult(result);
        }
        return result;
    }

    /**
     * DTO-facing method for retrieving screening result by ID with in-transaction mapping.
     */
    @Transactional(readOnly = true)
    public ScreeningResponseDto getScreeningResultDtoById(Long id) {
        ScreeningResult result = getScreeningResultById(id);
        return ScreeningResponseDto.fromEntity(result);
    }

    /**
     * Retrieves screening result entity for a specific job and resume combination.
     */
    @Transactional(readOnly = true)
    public Optional<ScreeningResult> getScreeningResult(Long jobId, Long resumeId) {
        JobDescription job = jobDescriptionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewJob(job);
        }
        return screeningResultRepository.findByJobDescriptionIdAndResumeId(jobId, resumeId);
    }

    /**
     * DTO-facing method for retrieving screening result for a job and resume pair.
     */
    @Transactional(readOnly = true)
    public Optional<ScreeningResponseDto> getScreeningResultDto(Long jobId, Long resumeId) {
        return getScreeningResult(jobId, resumeId).map(ScreeningResponseDto::fromEntity);
    }

    /**
     * Retrieves all screening result entities for a job, ordered by match score descending.
     */
    @Transactional(readOnly = true)
    public List<ScreeningResult> getScreeningResultsByJob(Long jobId) {
        JobDescription job = jobDescriptionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewJob(job);
        }
        return screeningResultRepository.findByJobDescriptionIdOrderByMatchScoreDesc(jobId);
    }

    /**
     * DTO-facing method for retrieving all screening results for a job.
     */
    @Transactional(readOnly = true)
    public List<ScreeningResponseDto> getScreeningResultDtosByJob(Long jobId) {
        return getScreeningResultsByJob(jobId).stream()
                .map(ScreeningResponseDto::fromEntity)
                .toList();
    }

    /**
     * Retrieves all screening result entities for a given resume across different jobs.
     */
    @Transactional(readOnly = true)
    public List<ScreeningResult> getScreeningResultsByResume(Long resumeId) {
        List<ScreeningResult> results = screeningResultRepository.findByResumeId(resumeId);
        if (securityAuthorizationService != null) {
            return results.stream()
                    .filter(sr -> {
                        try {
                            securityAuthorizationService.validateCanViewJob(sr.getJobDescription());
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .toList();
        }
        return results;
    }

    /**
     * DTO-facing method for retrieving all screening results for a resume.
     */
    @Transactional(readOnly = true)
    public List<ScreeningResponseDto> getScreeningResultDtosByResume(Long resumeId) {
        return getScreeningResultsByResume(resumeId).stream()
                .map(ScreeningResponseDto::fromEntity)
                .toList();
    }
}

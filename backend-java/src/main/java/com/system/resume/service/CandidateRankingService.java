package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.client.AiServiceClient.BatchRankRequest;
import com.system.resume.client.AiServiceClient.BatchRankResponse;
import com.system.resume.client.AiServiceClient.CandidateProfile;
import com.system.resume.client.AiServiceClient.RankedCandidate;
import com.system.resume.dto.CandidateRankingResponseDto;
import com.system.resume.model.Candidate;
import com.system.resume.model.CandidateRanking;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeSkill;
import com.system.resume.model.ScreeningResult;
import com.system.resume.repository.CandidateRankingRepository;
import com.system.resume.repository.JobDescriptionRepository;
import com.system.resume.repository.ResumeRepository;
import com.system.resume.repository.ResumeSkillRepository;
import com.system.resume.repository.ScreeningResultRepository;
import com.system.resume.security.SecurityAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Service managing candidate batch ranking, leaderboard generation,
 * recruiter review workflows (shortlisting, notes, review statuses),
 * and in-transaction DTO projections.
 *
 * Adheres strictly to isolated transaction boundaries:
 * - Read phase extracts unmanaged data structures before external calls.
 * - Batch ranking HTTP call to Python microservice occurs outside any DB transaction.
 * - Atomic leaderboard replacement inside a write transaction uses explicit repository flush
 *   to avoid unique constraint conflicts on (job_description_id, rank_position).
 * - DTO projections happen inside active transactions to prevent lazy loading issues.
 */
@Service
public class CandidateRankingService {

    private static final Logger logger = LoggerFactory.getLogger(CandidateRankingService.class);

    private final CandidateRankingRepository candidateRankingRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeSkillRepository resumeSkillRepository;
    private final AiServiceClient aiServiceClient;
    private final ScreeningService screeningService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final SecurityAuthorizationService securityAuthorizationService;

    @Autowired
    public CandidateRankingService(
            CandidateRankingRepository candidateRankingRepository,
            ScreeningResultRepository screeningResultRepository,
            JobDescriptionRepository jobDescriptionRepository,
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            AiServiceClient aiServiceClient,
            ScreeningService screeningService,
            PlatformTransactionManager transactionManager,
            SecurityAuthorizationService securityAuthorizationService
    ) {
        this.candidateRankingRepository = candidateRankingRepository;
        this.screeningResultRepository = screeningResultRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.resumeRepository = resumeRepository;
        this.resumeSkillRepository = resumeSkillRepository;
        this.aiServiceClient = aiServiceClient;
        this.screeningService = screeningService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.securityAuthorizationService = securityAuthorizationService;
    }

    public CandidateRankingService(
            CandidateRankingRepository candidateRankingRepository,
            ScreeningResultRepository screeningResultRepository,
            JobDescriptionRepository jobDescriptionRepository,
            ResumeRepository resumeRepository,
            ResumeSkillRepository resumeSkillRepository,
            AiServiceClient aiServiceClient,
            ScreeningService screeningService,
            PlatformTransactionManager transactionManager
    ) {
        this(candidateRankingRepository, screeningResultRepository, jobDescriptionRepository,
                resumeRepository, resumeSkillRepository, aiServiceClient, screeningService, transactionManager, null);
    }

    /**
     * Container record for capturing recruiter manual overrides before re-ranking.
     */
    private record PreservedDecision(
            String reviewStatus,
            String reviewerNotes,
            boolean isManualOverride
    ) {}

    /**
     * Batch ranks all screened candidates for a specific job description using default selection parameters
     * (topN = 10, minScore = 20.0). Preserves backward compatibility.
     *
     * @param jobId target job description ID
     * @return list of newly persisted CandidateRanking entities ordered by rank position
     */
    public List<CandidateRanking> rankCandidatesForJob(Long jobId) {
        return rankCandidatesForJob(jobId, 10, 20.0);
    }

    /**
     * Batch ranks all screened candidates for a specific job description with customizable Top-N selection quota
     * and minimum match percentage threshold.
     *
     * 1. Validates input parameters (topN > 0, 0.0 <= minScore <= 100.0).
     * 2. Reads job and candidate profiles into an immutable BatchRankRequest (read transaction).
     * 3. Calls Python AI service rankCandidates endpoint over HTTP (outside transaction).
     * 4. In atomic write transaction:
     *    a. Snapshots existing recruiter overrides (isManualOverride == true, status == REVIEWED, or non-blank notes).
     *    b. Deletes prior rankings and flushes immediately.
     *    c. Evaluates each candidate against Top-N quota and minimum score threshold.
     *    d. Generates an evidence-grounded AI decisionReason for every candidate.
     *    e. Restores preserved recruiter overrides without overwriting human decisions.
     *    f. Batch persists the new leaderboard.
     *
     * @param jobId target job description ID
     * @param topN requested number of candidates to select (must be > 0, defaults to 10 if null)
     * @param minScore minimum match percentage required (between 0.0 and 100.0 inclusive, defaults to 20.0 if null)
     * @return list of newly persisted CandidateRanking entities ordered by rank position
     */
    public List<CandidateRanking> rankCandidatesForJob(Long jobId, Integer topN, Double minScore) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null.");
        }
        JobDescription targetJob = jobDescriptionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));

        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageJob(targetJob);
        }

        final int effectiveTopN = (topN != null) ? topN : 10;
        if (effectiveTopN <= 0) {
            throw new IllegalArgumentException("topN must be greater than zero.");
        }

        final double effectiveMinScore = (minScore != null) ? minScore : 20.0;
        if (effectiveMinScore < 0.0 || effectiveMinScore > 100.0) {
            throw new IllegalArgumentException("minScore must be between 0.0 and 100.0 inclusive.");
        }

        // Phase 0: Auto-screen any eligible unscreened parsed resumes for this job
        autoScreenEligibleResumesForJob(jobId);

        // Phase 1: Read and prepare batch data outside write transaction
        BatchRankRequest rankRequest = readOnlyTransactionTemplate.execute(status -> {
            JobDescription job = jobDescriptionRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));

            // Fetch all completed screening results for this position
            List<ScreeningResult> completedScreenings = screeningResultRepository
                    .findByJobDescriptionIdAndScreeningStatus(jobId, "COMPLETED");

            if (completedScreenings.isEmpty()) {
                logger.info("No completed screenings found for Job ID: {}", jobId);
                return null;
            }

            List<CandidateProfile> candidateProfiles = new ArrayList<>();
            for (ScreeningResult sr : completedScreenings) {
                Resume resume = sr.getResume();
                if (resume == null || resume.getParsedText() == null || resume.getParsedText().isBlank()) {
                    continue;
                }

                Candidate candidate = resume.getCandidate();
                String candidateName = (candidate != null)
                        ? (candidate.getFirstName() + " " + candidate.getLastName()).trim()
                        : "Candidate #" + resume.getId();

                List<ResumeSkill> skills = resumeSkillRepository.findByResumeId(resume.getId());
                List<String> skillNames = skills.stream()
                        .map(ResumeSkill::getSkillName)
                        .toList();

                candidateProfiles.add(new CandidateProfile(
                        candidate != null ? candidate.getId() : null,
                        resume.getId(),
                        candidateName,
                        resume.getParsedText(),
                        skillNames
                ));
            }

            if (candidateProfiles.isEmpty()) {
                return null;
            }

            return new BatchRankRequest(
                    job.getId(),
                    job.getTitle(),
                    job.getDescriptionText(),
                    job.getRequiredSkills() != null ? job.getRequiredSkills() : Collections.emptyList(),
                    job.getMinExperienceYears() != null ? job.getMinExperienceYears() : 0,
                    candidateProfiles
            );
        });

        if (rankRequest == null || rankRequest.candidates().isEmpty()) {
            logger.warn("No valid candidate profiles available for ranking for Job ID: {}", jobId);
            return Collections.emptyList();
        }

        // Phase 2: Call Python AI Microservice over HTTP (outside DB transaction)
        logger.info("Calling AI service to rank {} candidates for Job ID: {} (topN={}, minScore={})",
                rankRequest.candidates().size(), jobId, effectiveTopN, effectiveMinScore);
        BatchRankResponse rankResponse = aiServiceClient.rankCandidates(rankRequest);

        if (rankResponse == null || rankResponse.rankings() == null) {
            throw new RuntimeException("AI ranking service returned empty response for Job ID: " + jobId);
        }

        // Phase 3: Atomic leaderboard replacement with explicit flush and override preservation
        return transactionTemplate.execute(status -> {
            JobDescription managedJob = jobDescriptionRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));

            // 1. Snapshot existing recruiter overrides before deletion
            List<CandidateRanking> priorRankings = candidateRankingRepository
                    .findByJobDescriptionIdOrderByRankPositionAsc(jobId);
            Map<Long, PreservedDecision> preservedOverrides = new HashMap<>();
            for (CandidateRanking prior : priorRankings) {
                boolean hasManualOverride = Boolean.TRUE.equals(prior.getIsManualOverride());
                boolean isReviewed = "REVIEWED".equalsIgnoreCase(prior.getReviewStatus());
                boolean hasNotes = prior.getReviewerNotes() != null && !prior.getReviewerNotes().isBlank();

                if (hasManualOverride || isReviewed || hasNotes) {
                    Long resumeId = (prior.getScreeningResult() != null && prior.getScreeningResult().getResume() != null)
                            ? prior.getScreeningResult().getResume().getId()
                            : null;
                    if (resumeId != null) {
                        preservedOverrides.put(resumeId, new PreservedDecision(
                                prior.getReviewStatus(),
                                prior.getReviewerNotes(),
                                hasManualOverride || isReviewed || hasNotes
                        ));
                    }
                }
            }

            // 2. Delete prior rankings for this job
            candidateRankingRepository.deleteByJobDescriptionId(jobId);

            // CRITICAL: Synchronize DELETE to database immediately before executing INSERTs
            // to satisfy the unique constraint uk_job_ranking_position (job_description_id, rank_position)
            candidateRankingRepository.flush();

            // 3. Construct new rankings with Top-N selection logic and explanations
            int totalCandidates = rankResponse.rankings().size();
            List<CandidateRanking> newRankings = new ArrayList<>();

            for (RankedCandidate ranked : rankResponse.rankings()) {
                ScreeningResult managedScreening = screeningResultRepository
                        .findByJobDescriptionIdAndResumeId(jobId, ranked.resume_id())
                        .orElseThrow(() -> new IllegalStateException(
                                "ScreeningResult missing for Job ID " + jobId + " and Resume ID " + ranked.resume_id()));

                boolean isWithinTopN = ranked.rank_position() <= effectiveTopN;
                boolean meetsMinScore = ranked.final_score() >= effectiveMinScore;
                boolean isSelected = isWithinTopN && meetsMinScore;
                String automaticStatus = isSelected ? "SELECTED" : "REJECTED";

                String decisionReason = generateDecisionReason(
                        ranked,
                        ranked.rank_position(),
                        totalCandidates,
                        effectiveTopN,
                        effectiveMinScore,
                        isSelected,
                        managedScreening
                );

                PreservedDecision override = preservedOverrides.get(ranked.resume_id());
                String reviewStatus;
                String reviewerNotes;
                boolean isManualOverride;

                if (override != null) {
                    reviewStatus = override.reviewStatus();
                    reviewerNotes = override.reviewerNotes();
                    isManualOverride = override.isManualOverride();
                } else {
                    reviewStatus = automaticStatus;
                    reviewerNotes = null;
                    isManualOverride = false;
                }

                CandidateRanking ranking = new CandidateRanking(
                        managedJob,
                        managedScreening,
                        ranked.rank_position(),
                        BigDecimal.valueOf(ranked.final_score()),
                        reviewStatus,
                        decisionReason,
                        reviewerNotes,
                        isManualOverride
                );
                newRankings.add(ranking);
            }

            List<CandidateRanking> savedRankings = candidateRankingRepository.saveAll(newRankings);
            logger.info("Successfully ranked and persisted {} candidates for Job ID: {} ({} overrides preserved)",
                    savedRankings.size(), jobId, preservedOverrides.size());
            return savedRankings;
        });
    }

    /**
     * Automatically screens any eligible parsed resumes that do not yet have
     * a screening result for the target job description.
     *
     * Processes each resume with complete failure isolation.
     * Calls to the Python AI service occur outside database transactions.
     * Respects the unique (job_description_id, resume_id) constraint and avoids
     * unnecessary duplicate or overwriting operations.
     *
     * @param jobId target job description ID
     */
    private void autoScreenEligibleResumesForJob(Long jobId) {
        List<Resume> unscreenedResumes;
        try {
            unscreenedResumes = readOnlyTransactionTemplate.execute(status ->
                    resumeRepository.findUnscreenedParsedResumesForJob(jobId)
            );
        } catch (Exception e) {
            logger.warn("Could not query unscreened resumes for Job ID {}: {}", jobId, e.getMessage());
            return;
        }

        if (unscreenedResumes == null || unscreenedResumes.isEmpty()) {
            logger.debug("No unscreened eligible resumes found for Job ID: {}", jobId);
            return;
        }

        logger.info("Found {} unscreened eligible resume(s) for Job ID {}. Initiating auto-screening...",
                unscreenedResumes.size(), jobId);

        int successfulCount = 0;
        int failedCount = 0;

        for (Resume resume : unscreenedResumes) {
            if (resume == null || resume.getId() == null) {
                continue;
            }

            try {
                // screeningService.screenCandidateResume manages its own read transaction,
                // external HTTP AI call outside transaction, and atomic persistence.
                screeningService.screenCandidateResume(jobId, resume.getId());
                successfulCount++;
                logger.info("Auto-screened resume ID {} for Job ID {}", resume.getId(), jobId);
            } catch (Exception ex) {
                failedCount++;
                // Failure isolation: log with context without exposing sensitive paths or personal data
                logger.warn("Auto-screening failed for resume ID {} on Job ID {}: {}",
                        resume.getId(), jobId, ex.getMessage());
            }
        }

        logger.info("Auto-screening completed for Job ID {}: {} succeeded, {} failed out of {} eligible resume(s).",
                jobId, successfulCount, failedCount, unscreenedResumes.size());
    }

    /**
     * Generates a grounded, natural-language explanation justifying why a candidate was selected or rejected.
     */
    private String generateDecisionReason(
            RankedCandidate ranked,
            int rankPosition,
            int totalCandidates,
            int topN,
            double minScore,
            boolean isSelected,
            ScreeningResult screening
    ) {
        List<String> matched = (ranked.matched_skills() != null && !ranked.matched_skills().isEmpty())
                ? ranked.matched_skills()
                : (screening != null && screening.getMatchedSkills() != null ? screening.getMatchedSkills() : Collections.emptyList());

        List<String> missing = (ranked.missing_skills() != null && !ranked.missing_skills().isEmpty())
                ? ranked.missing_skills()
                : (screening != null && screening.getMissingSkills() != null ? screening.getMissingSkills() : Collections.emptyList());

        double finalScore = ranked.final_score();
        double skillsScore = ranked.skills_score();
        double expScore = ranked.experience_score();

        String aiSummary = (ranked.ai_summary() != null && !ranked.ai_summary().isBlank())
                ? ranked.ai_summary()
                : (screening != null ? screening.getAiSummary() : null);

        String expSnippet = null;
        if (aiSummary != null && !aiSummary.isBlank()) {
            for (String sentence : aiSummary.split("\\.")) {
                String trimmed = sentence.trim();
                if (trimmed.toLowerCase().contains("experience")) {
                    expSnippet = trimmed;
                    break;
                }
            }
        }

        StringBuilder reason = new StringBuilder();

        if (isSelected) {
            reason.append(String.format(Locale.ROOT,
                    "Selected: Ranked #%d of %d applicant(s) (within requested Top %d quota). ",
                    rankPosition, totalCandidates, topN));
            reason.append(String.format(Locale.ROOT,
                    "Match score of %.1f%% met the minimum eligibility threshold of %.1f%% (Skills: %.1f%%, Experience: %.1f%%). ",
                    finalScore, minScore, skillsScore, expScore));
            if (!matched.isEmpty()) {
                reason.append("Demonstrates required skill(s): ")
                        .append(String.join(", ", matched))
                        .append(". ");
            }
            if (expSnippet != null) {
                reason.append(expSnippet).append(". ");
            }
            reason.append("Qualified for selection based on composite score ranking.");
        } else {
            boolean isWithinTopN = rankPosition <= topN;
            boolean meetsScore = finalScore >= minScore;

            if (!isWithinTopN && !meetsScore) {
                reason.append(String.format(Locale.ROOT,
                        "Rejected: Ranked #%d of %d applicant(s) (outside requested Top %d quota). ",
                        rankPosition, totalCandidates, topN));
                reason.append(String.format(Locale.ROOT,
                        "Match score of %.1f%% fell below the minimum eligibility threshold of %.1f%% (Skills: %.1f%%, Experience: %.1f%%). ",
                        finalScore, minScore, skillsScore, expScore));
            } else if (!isWithinTopN) {
                reason.append(String.format(Locale.ROOT,
                        "Rejected: Ranked #%d of %d applicant(s) (fell outside the requested Top %d quota). ",
                        rankPosition, totalCandidates, topN));
                reason.append(String.format(Locale.ROOT,
                        "Although match score of %.1f%% met the minimum threshold of %.1f%%, higher-ranking candidates filled the available selection quota. ",
                        finalScore, minScore));
            } else {
                reason.append(String.format(Locale.ROOT,
                        "Rejected: Ranked #%d of %d applicant(s) (within Top %d rank positions). ",
                        rankPosition, totalCandidates, topN));
                reason.append(String.format(Locale.ROOT,
                        "Match score of %.1f%% fell below the minimum eligibility threshold of %.1f%% (Skills: %.1f%%, Experience: %.1f%%). ",
                        finalScore, minScore, skillsScore, expScore));
            }

            if (!missing.isEmpty()) {
                reason.append("Missing required skill(s): ")
                        .append(String.join(", ", missing))
                        .append(". ");
            }
            if (expSnippet != null) {
                reason.append(expSnippet).append(". ");
            }
        }

        return reason.toString().trim();
    }

    /**
     * DTO-facing batch ranking method with in-transaction mapping using default selection parameters.
     */
    public List<CandidateRankingResponseDto> rankCandidatesForJobDto(Long jobId) {
        return rankCandidatesForJobDto(jobId, 10, 20.0);
    }

    /**
     * DTO-facing batch ranking method with in-transaction mapping accepting topN and minScore.
     */
    public List<CandidateRankingResponseDto> rankCandidatesForJobDto(Long jobId, Integer topN, Double minScore) {
        rankCandidatesForJob(jobId, topN, minScore);
        return getLeaderboardDtosByJob(jobId, null);
    }

    /**
     * Stateless ad-hoc batch ranking without database persistence.
     */
    public BatchRankResponse rankCandidatesAdHoc(BatchRankRequest request) {
        return aiServiceClient.rankCandidates(request);
    }

    /**
     * Retrieves the candidate leaderboard entities for a job ordered by rank position ascending.
     */
    @Transactional(readOnly = true)
    public List<CandidateRanking> getLeaderboardByJob(Long jobId) {
        JobDescription job = jobDescriptionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewJob(job);
        }
        return candidateRankingRepository.findByJobDescriptionIdOrderByRankPositionAsc(jobId);
    }

    /**
     * Retrieves ranking entities for a job filtered by review status (e.g. SHORTLISTED, REVIEWED, REJECTED).
     */
    @Transactional(readOnly = true)
    public List<CandidateRanking> getRankingsByReviewStatus(Long jobId, String reviewStatus) {
        JobDescription job = jobDescriptionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewJob(job);
        }
        if (reviewStatus == null || reviewStatus.isBlank()) {
            return candidateRankingRepository.findByJobDescriptionIdOrderByRankPositionAsc(jobId);
        }
        return candidateRankingRepository.findByJobDescriptionIdAndReviewStatusOrderByRankPositionAsc(
                jobId, reviewStatus.trim().toUpperCase());
    }

    /**
     * DTO-facing method for retrieving candidate leaderboard, with optional review status filter.
     */
    @Transactional(readOnly = true)
    public List<CandidateRankingResponseDto> getLeaderboardDtosByJob(Long jobId, String reviewStatus) {
        List<CandidateRanking> rankings = (reviewStatus != null && !reviewStatus.isBlank())
                ? getRankingsByReviewStatus(jobId, reviewStatus)
                : getLeaderboardByJob(jobId);

        return rankings.stream()
                .map(CandidateRankingResponseDto::fromEntity)
                .toList();
    }

    /**
     * Updates recruiter review status and notes for a ranked candidate entity.
     */
    @Transactional
    public CandidateRanking updateReviewStatus(Long rankingId, String reviewStatus, String reviewerNotes) {
        if (rankingId == null) {
            throw new IllegalArgumentException("Ranking ID cannot be null.");
        }
        if (reviewStatus == null || reviewStatus.isBlank()) {
            throw new IllegalArgumentException("Review status cannot be blank.");
        }

        CandidateRanking ranking = candidateRankingRepository.findById(rankingId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate ranking not found with ID: " + rankingId));

        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanReviewRanking(ranking);
            ranking.setReviewedBy(securityAuthorizationService.getCurrentUser());
            ranking.setReviewedAt(LocalDateTime.now());
        }

        ranking.setReviewStatus(reviewStatus.trim().toUpperCase());
        if (reviewerNotes != null) {
            ranking.setReviewerNotes(reviewerNotes.trim());
        }
        ranking.setIsManualOverride(true);

        CandidateRanking saved = candidateRankingRepository.save(ranking);
        logger.info("Updated review status for ranking ID {} to '{}'", rankingId, saved.getReviewStatus());
        return saved;
    }

    /**
     * DTO-facing method for updating recruiter review status and notes.
     */
    @Transactional
    public CandidateRankingResponseDto updateReviewStatusDto(Long rankingId, String reviewStatus, String reviewerNotes) {
        CandidateRanking updated = updateReviewStatus(rankingId, reviewStatus, reviewerNotes);
        return CandidateRankingResponseDto.fromEntity(updated);
    }
}

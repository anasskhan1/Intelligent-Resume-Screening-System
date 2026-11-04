package com.system.resume.dto;

import com.system.resume.model.Candidate;
import com.system.resume.model.CandidateRanking;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ScreeningResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data Transfer Object for candidate ranking and leaderboard response serialization.
 * Enriched with contact details, resume file names, skills breakdowns, and AI narrative summaries.
 */
public record CandidateRankingResponseDto(
        Long id,
        Long jobId,
        String jobTitle,
        Long screeningResultId,
        Long resumeId,
        Long candidateId,
        String candidateName,
        String candidateEmail,
        String candidatePhone,
        String resumeFileName,
        Integer rankPosition,
        BigDecimal finalScore,
        BigDecimal skillsScore,
        BigDecimal experienceScore,
        Integer experienceYears,
        String reviewStatus,
        String decisionReason,
        String reviewerNotes,
        Boolean isManualOverride,
        LocalDateTime rankedAt,
        List<String> matchedSkills,
        List<String> missingSkills,
        String aiSummary
) {
    private static final Pattern EXP_PATTERN = Pattern.compile("(?i)experience\\s*\\((\\d+)\\+?\\s*years?\\)");

    public static CandidateRankingResponseDto fromEntity(CandidateRanking ranking) {
        if (ranking == null) {
            return null;
        }

        JobDescription job = ranking.getJobDescription();
        Long jobId = job != null ? job.getId() : null;
        String jobTitle = job != null ? job.getTitle() : null;

        ScreeningResult screening = ranking.getScreeningResult();
        Long screeningResultId = screening != null ? screening.getId() : null;

        Resume resume = screening != null ? screening.getResume() : null;
        Long resumeId = resume != null ? resume.getId() : null;
        String resumeFileName = resume != null ? resume.getFileName() : null;

        Candidate candidate = resume != null ? resume.getCandidate() : null;
        Long candidateId = candidate != null ? candidate.getId() : null;
        String candidateName = candidate != null
                ? (candidate.getFirstName() + " " + candidate.getLastName()).trim()
                : null;
        String candidateEmail = candidate != null ? candidate.getEmail() : null;
        String candidatePhone = candidate != null ? candidate.getPhone() : null;

        List<String> matchedSkills = (screening != null && screening.getMatchedSkills() != null)
                ? new ArrayList<>(screening.getMatchedSkills())
                : Collections.emptyList();

        List<String> missingSkills = (screening != null && screening.getMissingSkills() != null)
                ? new ArrayList<>(screening.getMissingSkills())
                : Collections.emptyList();

        BigDecimal skillsScore = screening != null ? screening.getSkillsScore() : null;
        BigDecimal experienceScore = screening != null ? screening.getExperienceScore() : null;
        String aiSummary = screening != null ? screening.getAiSummary() : null;

        Integer experienceYears = null;
        if (aiSummary != null) {
            Matcher m = EXP_PATTERN.matcher(aiSummary);
            if (m.find()) {
                try {
                    experienceYears = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }

        return new CandidateRankingResponseDto(
                ranking.getId(),
                jobId,
                jobTitle,
                screeningResultId,
                resumeId,
                candidateId,
                candidateName,
                candidateEmail,
                candidatePhone,
                resumeFileName,
                ranking.getRankPosition(),
                ranking.getFinalScore(),
                skillsScore,
                experienceScore,
                experienceYears,
                ranking.getReviewStatus(),
                ranking.getDecisionReason(),
                ranking.getReviewerNotes(),
                ranking.getIsManualOverride(),
                ranking.getRankedAt(),
                matchedSkills,
                missingSkills,
                aiSummary
        );
    }
}

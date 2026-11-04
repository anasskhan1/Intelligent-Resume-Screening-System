package com.system.resume.dto;

import com.system.resume.model.Candidate;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ScreeningResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Transfer Object for resume screening match results.
 */
public record ScreeningResponseDto(
        Long id,
        Long jobId,
        String jobTitle,
        Long resumeId,
        Long candidateId,
        String candidateName,
        BigDecimal matchScore,
        BigDecimal skillsScore,
        BigDecimal experienceScore,
        List<String> matchedSkills,
        List<String> missingSkills,
        String aiSummary,
        String screeningStatus,
        LocalDateTime screenedAt
) {
    public static ScreeningResponseDto fromEntity(ScreeningResult result) {
        if (result == null) {
            return null;
        }

        JobDescription job = result.getJobDescription();
        Long jobId = job != null ? job.getId() : null;
        String jobTitle = job != null ? job.getTitle() : null;

        Resume resume = result.getResume();
        Long resumeId = resume != null ? resume.getId() : null;
        Candidate candidate = resume != null ? resume.getCandidate() : null;
        Long candidateId = candidate != null ? candidate.getId() : null;
        String candidateName = candidate != null
                ? (candidate.getFirstName() + " " + candidate.getLastName()).trim()
                : null;

        return new ScreeningResponseDto(
                result.getId(),
                jobId,
                jobTitle,
                resumeId,
                candidateId,
                candidateName,
                result.getMatchScore(),
                result.getSkillsScore(),
                result.getExperienceScore(),
                result.getMatchedSkills() != null ? new ArrayList<>(result.getMatchedSkills()) : new ArrayList<>(),
                result.getMissingSkills() != null ? new ArrayList<>(result.getMissingSkills()) : new ArrayList<>(),
                result.getAiSummary(),
                result.getScreeningStatus(),
                result.getScreenedAt()
        );
    }
}

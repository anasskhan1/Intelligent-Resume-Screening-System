package com.system.resume.dto;

import com.system.resume.model.ResumeSkill;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for extracted resume skill details.
 */
public record ResumeSkillDto(
        Long id,
        Long resumeId,
        String skillName,
        String category,
        BigDecimal confidenceScore,
        LocalDateTime createdAt
) {
    public static ResumeSkillDto fromEntity(ResumeSkill skill) {
        if (skill == null) {
            return null;
        }
        return new ResumeSkillDto(
                skill.getId(),
                skill.getResume() != null ? skill.getResume().getId() : null,
                skill.getSkillName(),
                skill.getCategory(),
                skill.getConfidenceScore(),
                skill.getCreatedAt()
        );
    }
}

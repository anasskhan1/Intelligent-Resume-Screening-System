package com.system.resume.dto;

import com.system.resume.model.JobDescription;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Transfer Object for job description response serialization.
 */
public record JobDescriptionResponseDto(
        Long id,
        String title,
        String department,
        String location,
        String descriptionText,
        List<String> requiredSkills,
        Integer minExperienceYears,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long createdByUserId
) {
    public JobDescriptionResponseDto(Long id, String title, String department, String location,
                                    String descriptionText, List<String> requiredSkills,
                                    Integer minExperienceYears, String status,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, title, department, location, descriptionText, requiredSkills, minExperienceYears, status, createdAt, updatedAt, null);
    }

    public static JobDescriptionResponseDto fromEntity(JobDescription job) {
        if (job == null) {
            return null;
        }
        return new JobDescriptionResponseDto(
                job.getId(),
                job.getTitle(),
                job.getDepartment(),
                job.getLocation(),
                job.getDescriptionText(),
                job.getRequiredSkills() != null ? new ArrayList<>(job.getRequiredSkills()) : new ArrayList<>(),
                job.getMinExperienceYears(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getCreatedBy() != null ? job.getCreatedBy().getId() : null
        );
    }
}

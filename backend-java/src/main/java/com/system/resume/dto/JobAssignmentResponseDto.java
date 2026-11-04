package com.system.resume.dto;

import com.system.resume.model.JobHiringManager;

import java.time.LocalDateTime;

/**
 * Data Transfer Object representing an assigned hiring manager on a job posting.
 */
public record JobAssignmentResponseDto(
        Long id,
        Long jobId,
        Long hiringManagerId,
        String hiringManagerEmail,
        String hiringManagerName,
        Long assignedByUserId,
        LocalDateTime assignedAt
) {
    public static JobAssignmentResponseDto fromEntity(JobHiringManager jhm) {
        if (jhm == null) {
            return null;
        }
        return new JobAssignmentResponseDto(
                jhm.getId(),
                jhm.getJobDescription() != null ? jhm.getJobDescription().getId() : null,
                jhm.getHiringManager() != null ? jhm.getHiringManager().getId() : null,
                jhm.getHiringManager() != null ? jhm.getHiringManager().getEmail() : null,
                jhm.getHiringManager() != null ? jhm.getHiringManager().getFullName() : null,
                jhm.getAssignedBy() != null ? jhm.getAssignedBy().getId() : null,
                jhm.getAssignedAt()
        );
    }
}


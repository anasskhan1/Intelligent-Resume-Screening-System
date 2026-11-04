package com.system.resume.dto;

import com.system.resume.model.ResumeBatch;

import java.time.LocalDateTime;

/**
 * Data Transfer Object representing high-level resume batch processing status.
 */
public record ResumeBatchResponseDto(
        Long id,
        String batchName,
        Long jobDescriptionId,
        String jobTitle,
        Integer totalFiles,
        Integer processedFiles,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ResumeBatchResponseDto fromEntity(ResumeBatch batch) {
        if (batch == null) {
            return null;
        }
        Long jobId = null;
        String jobTitle = null;
        if (batch.getJobDescription() != null) {
            jobId = batch.getJobDescription().getId();
            jobTitle = batch.getJobDescription().getTitle();
        }
        return new ResumeBatchResponseDto(
                batch.getId(),
                batch.getBatchName(),
                jobId,
                jobTitle,
                batch.getTotalFiles(),
                batch.getProcessedFiles(),
                batch.getStatus(),
                batch.getCreatedAt(),
                batch.getUpdatedAt()
        );
    }
}


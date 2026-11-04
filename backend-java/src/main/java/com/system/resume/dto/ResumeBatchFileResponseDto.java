package com.system.resume.dto;

import com.system.resume.model.ResumeBatchFile;

import java.time.LocalDateTime;

/**
 * Data Transfer Object representing individual resume file status within a batch.
 */
public record ResumeBatchFileResponseDto(
        Long id,
        Long batchId,
        String fileName,
        String fileHash,
        String status,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ResumeBatchFileResponseDto fromEntity(ResumeBatchFile batchFile) {
        if (batchFile == null) {
            return null;
        }
        Long batchId = batchFile.getBatch() != null ? batchFile.getBatch().getId() : null;
        return new ResumeBatchFileResponseDto(
                batchFile.getId(),
                batchId,
                batchFile.getFileName(),
                batchFile.getFileHash(),
                batchFile.getStatus(),
                batchFile.getErrorMessage(),
                batchFile.getCreatedAt(),
                batchFile.getUpdatedAt()
        );
    }
}


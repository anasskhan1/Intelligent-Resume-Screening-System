package com.system.resume.dto;

import com.system.resume.model.ResumeBatch;
import com.system.resume.model.ResumeBatchFile;

import java.util.Collections;
import java.util.List;

/**
 * Composite DTO containing batch summary and detailed list of file outcomes.
 */
public record ResumeBatchDetailResponseDto(
        ResumeBatchResponseDto batch,
        List<ResumeBatchFileResponseDto> files
) {
    public static ResumeBatchDetailResponseDto of(ResumeBatch batch, List<ResumeBatchFile> files) {
        if (batch == null) {
            return null;
        }
        List<ResumeBatchFileResponseDto> fileDtos = (files != null)
                ? files.stream().map(ResumeBatchFileResponseDto::fromEntity).toList()
                : Collections.emptyList();
        return new ResumeBatchDetailResponseDto(ResumeBatchResponseDto.fromEntity(batch), fileDtos);
    }
}


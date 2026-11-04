package com.system.resume.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Data Transfer Object for initiating a candidate-job screening evaluation.
 */
public record ScreeningRequestDto(
        @NotNull(message = "Job ID is required")
        Long jobId,

        @NotNull(message = "Resume ID is required")
        Long resumeId
) {}

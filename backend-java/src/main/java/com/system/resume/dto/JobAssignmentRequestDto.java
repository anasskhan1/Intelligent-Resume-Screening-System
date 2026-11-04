package com.system.resume.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Data Transfer Object for assigning a hiring manager to a job.
 */
public record JobAssignmentRequestDto(
        @NotNull(message = "hiringManagerId is required")
        Long hiringManagerId
) {}


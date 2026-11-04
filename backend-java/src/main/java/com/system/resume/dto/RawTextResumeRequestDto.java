package com.system.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Data Transfer Object for raw text resume submission.
 */
public record RawTextResumeRequestDto(
        @NotNull(message = "Candidate ID is required")
        Long candidateId,

        @NotBlank(message = "Resume text cannot be blank")
        String text,

        @Size(max = 255, message = "Title cannot exceed 255 characters")
        String title
) {}

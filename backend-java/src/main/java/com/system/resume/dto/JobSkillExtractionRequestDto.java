package com.system.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for extracting required skills from job description text
 * via the Python AI microservice without persisting the job.
 */
public record JobSkillExtractionRequestDto(
        @NotBlank(message = "Job description text is required")
        @Size(max = 50000, message = "Job description text must not exceed 50,000 characters")
        String descriptionText
) {}


package com.system.resume.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Data Transfer Object for creating or updating a job description.
 */
public record JobDescriptionRequestDto(
        @NotBlank(message = "Job title is required")
        @Size(max = 255, message = "Job title cannot exceed 255 characters")
        String title,

        @Size(max = 100, message = "Department cannot exceed 100 characters")
        String department,

        @Size(max = 100, message = "Location cannot exceed 100 characters")
        String location,

        @NotBlank(message = "Job description text is required")
        String descriptionText,

        List<String> requiredSkills,

        @Min(value = 0, message = "Minimum experience years cannot be negative")
        Integer minExperienceYears,

        @Pattern(regexp = "^(ACTIVE|INACTIVE|CLOSED)?$", message = "Status must be ACTIVE, INACTIVE, or CLOSED")
        String status
) {}

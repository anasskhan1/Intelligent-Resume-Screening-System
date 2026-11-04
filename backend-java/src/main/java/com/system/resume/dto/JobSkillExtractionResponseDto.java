package com.system.resume.dto;

import java.util.List;

/**
 * Response payload containing AI-extracted skills from job description text.
 */
public record JobSkillExtractionResponseDto(
        List<String> skills,
        int wordCount,
        boolean success,
        String message
) {}


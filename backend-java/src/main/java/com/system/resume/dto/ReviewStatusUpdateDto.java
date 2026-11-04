package com.system.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Data Transfer Object for updating a candidate's review status on a job ranking leaderboard.
 */
public record ReviewStatusUpdateDto(
        @NotBlank(message = "Review status is required")
        @Pattern(regexp = "^(SELECTED|SHORTLISTED|REVIEWED|REJECTED)$", message = "Review status must be SELECTED, SHORTLISTED, REVIEWED, or REJECTED")
        String reviewStatus,

        String reviewerNotes
) {}

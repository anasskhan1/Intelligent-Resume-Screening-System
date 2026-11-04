package com.system.resume.dto;

import com.system.resume.model.Candidate;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for candidate response serialization.
 */
public record CandidateResponseDto(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CandidateResponseDto fromEntity(Candidate candidate) {
        if (candidate == null) {
            return null;
        }
        return new CandidateResponseDto(
                candidate.getId(),
                candidate.getFirstName(),
                candidate.getLastName(),
                candidate.getEmail(),
                candidate.getPhone(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }
}

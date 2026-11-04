package com.system.resume.dto;

import com.system.resume.model.Candidate;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeSkill;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Data Transfer Object for resume details, candidate linkage, and extracted skills.
 */
public record ResumeResponseDto(
        Long id,
        Long candidateId,
        String candidateName,
        String fileName,
        String fileType,
        Long fileSizeBytes,
        String parsedText,
        String processingStatus,
        LocalDateTime uploadedAt,
        LocalDateTime updatedAt,
        List<ResumeSkillDto> skills
) {
    public static ResumeResponseDto fromEntity(Resume resume, List<ResumeSkill> skills) {
        if (resume == null) {
            return null;
        }

        Candidate candidate = resume.getCandidate();
        Long candidateId = candidate != null ? candidate.getId() : null;
        String candidateName = (candidate != null)
                ? (candidate.getFirstName() + " " + candidate.getLastName()).trim()
                : null;

        List<ResumeSkillDto> skillDtos = (skills != null)
                ? skills.stream().map(ResumeSkillDto::fromEntity).toList()
                : Collections.emptyList();

        return new ResumeResponseDto(
                resume.getId(),
                candidateId,
                candidateName,
                resume.getFileName(),
                resume.getFileType(),
                resume.getFileSizeBytes(),
                resume.getParsedText(),
                resume.getProcessingStatus(),
                resume.getUploadedAt(),
                resume.getUpdatedAt(),
                skillDtos
        );
    }
}

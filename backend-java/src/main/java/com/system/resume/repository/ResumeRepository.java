package com.system.resume.repository;

import com.system.resume.model.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResumeRepository extends JpaRepository<Resume, Long> {

    List<Resume> findByCandidateId(Long candidateId);

    List<Resume> findByCandidateIdOrderByUploadedAtDesc(Long candidateId);

    Optional<Resume> findTopByCandidateIdOrderByUploadedAtDesc(Long candidateId);

    List<Resume> findByProcessingStatus(String processingStatus);

    Optional<Resume> findByFileHash(String fileHash);

    boolean existsByFileHash(String fileHash);

    @Query("SELECT r FROM Resume r WHERE r.processingStatus = 'PARSED' " +
           "AND r.parsedText IS NOT NULL " +
           "AND NOT EXISTS (SELECT sr FROM ScreeningResult sr WHERE sr.jobDescription.id = :jobId AND sr.resume.id = r.id AND sr.screeningStatus = 'COMPLETED')")
    List<Resume> findUnscreenedParsedResumesForJob(@Param("jobId") Long jobId);
}

package com.system.resume.repository;

import com.system.resume.model.JobDescription;
import com.system.resume.model.ScreeningResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScreeningResultRepository extends JpaRepository<ScreeningResult, Long> {

    Optional<ScreeningResult> findByJobDescriptionIdAndResumeId(Long jobDescriptionId, Long resumeId);

    boolean existsByJobDescriptionIdAndResumeId(Long jobDescriptionId, Long resumeId);

    List<ScreeningResult> findByJobDescriptionIdOrderByMatchScoreDesc(Long jobDescriptionId);

    List<ScreeningResult> findByResumeId(Long resumeId);

    List<ScreeningResult> findByJobDescriptionIdAndScreeningStatus(Long jobDescriptionId, String screeningStatus);

    @Query("SELECT DISTINCT sr.jobDescription FROM ScreeningResult sr WHERE sr.resume.candidate.id = :candidateId")
    List<JobDescription> findJobDescriptionsByCandidateId(@Param("candidateId") Long candidateId);

    @Query("SELECT DISTINCT sr.jobDescription FROM ScreeningResult sr WHERE sr.resume.id = :resumeId")
    List<JobDescription> findJobDescriptionsByResumeId(@Param("resumeId") Long resumeId);
}


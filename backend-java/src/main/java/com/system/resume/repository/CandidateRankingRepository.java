package com.system.resume.repository;

import com.system.resume.model.CandidateRanking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CandidateRankingRepository extends JpaRepository<CandidateRanking, Long> {

    List<CandidateRanking> findByJobDescriptionIdOrderByRankPositionAsc(Long jobDescriptionId);

    Optional<CandidateRanking> findByJobDescriptionIdAndRankPosition(Long jobDescriptionId, Integer rankPosition);

    Optional<CandidateRanking> findByJobDescriptionIdAndScreeningResultId(Long jobDescriptionId, Long screeningResultId);

    List<CandidateRanking> findByJobDescriptionIdAndReviewStatusOrderByRankPositionAsc(Long jobDescriptionId, String reviewStatus);

    void deleteByJobDescriptionId(Long jobDescriptionId);
}

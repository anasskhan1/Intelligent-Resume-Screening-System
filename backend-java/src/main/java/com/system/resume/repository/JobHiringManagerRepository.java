package com.system.resume.repository;

import com.system.resume.model.JobHiringManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobHiringManagerRepository extends JpaRepository<JobHiringManager, Long> {

    boolean existsByJobDescriptionIdAndHiringManagerId(Long jobDescriptionId, Long hiringManagerId);

    List<JobHiringManager> findByJobDescriptionId(Long jobDescriptionId);

    List<JobHiringManager> findByHiringManagerId(Long hiringManagerId);

    Optional<JobHiringManager> findByJobDescriptionIdAndHiringManagerId(Long jobDescriptionId, Long hiringManagerId);

    void deleteByJobDescriptionIdAndHiringManagerId(Long jobDescriptionId, Long hiringManagerId);

    void deleteByJobDescriptionId(Long jobDescriptionId);
}


package com.system.resume.repository;

import com.system.resume.model.ResumeBatch;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResumeBatchRepository extends JpaRepository<ResumeBatch, Long> {

    List<ResumeBatch> findByStatus(String status);

    @EntityGraph(attributePaths = {"jobDescription"})
    List<ResumeBatch> findAllByOrderByCreatedAtDesc();

    @Override
    @EntityGraph(attributePaths = {"jobDescription"})
    Optional<ResumeBatch> findById(Long id);

    @EntityGraph(attributePaths = {"jobDescription"})
    List<ResumeBatch> findByUploadedByIdOrderByCreatedAtDesc(Long uploadedById);
}


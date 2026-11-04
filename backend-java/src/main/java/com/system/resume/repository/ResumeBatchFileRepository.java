package com.system.resume.repository;

import com.system.resume.model.ResumeBatchFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResumeBatchFileRepository extends JpaRepository<ResumeBatchFile, Long> {

    List<ResumeBatchFile> findByBatchId(Long batchId);

    List<ResumeBatchFile> findByBatchIdOrderByCreatedAtAsc(Long batchId);

    List<ResumeBatchFile> findByBatchIdAndStatus(Long batchId, String status);

    long countByBatchIdAndStatus(Long batchId, String status);

    long countByBatchId(Long batchId);

    Optional<ResumeBatchFile> findByFileHash(String fileHash);
}


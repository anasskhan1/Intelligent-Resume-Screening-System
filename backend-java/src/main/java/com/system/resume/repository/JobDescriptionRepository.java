package com.system.resume.repository;

import com.system.resume.model.JobDescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {

    List<JobDescription> findByStatus(String status);

    List<JobDescription> findAllByOrderByCreatedAtDesc();

    List<JobDescription> findByDepartmentIgnoreCaseAndStatus(String department, String status);

    List<JobDescription> findByTitleContainingIgnoreCase(String keyword);

    List<JobDescription> findByCreatedByIdOrderByCreatedAtDesc(Long recruiterId);

    List<JobDescription> findByCreatedByIdAndStatus(Long recruiterId, String status);

    List<JobDescription> findByCreatedByIdAndDepartmentIgnoreCaseAndStatus(Long recruiterId, String department, String status);

    List<JobDescription> findByCreatedByIdAndTitleContainingIgnoreCase(Long recruiterId, String keyword);

    List<JobDescription> findByIdInOrderByCreatedAtDesc(List<Long> ids);

    List<JobDescription> findByIdInAndTitleContainingIgnoreCase(List<Long> ids, String keyword);
}

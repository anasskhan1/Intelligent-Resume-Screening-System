package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.dto.JobAssignmentResponseDto;
import com.system.resume.dto.JobDescriptionRequestDto;
import com.system.resume.dto.JobDescriptionResponseDto;
import com.system.resume.dto.JobSkillExtractionResponseDto;
import com.system.resume.model.JobDescription;
import com.system.resume.model.JobHiringManager;
import com.system.resume.model.User;
import com.system.resume.repository.JobDescriptionRepository;
import com.system.resume.repository.JobHiringManagerRepository;
import com.system.resume.repository.UserRepository;
import com.system.resume.security.SecurityAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service managing job descriptions, required skill specifications,
 * experience thresholds, recruitment statuses, recruiter ownership,
 * and hiring manager assignments.
 */
@Service
@Transactional(readOnly = true)
public class JobDescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(JobDescriptionService.class);

    private final JobDescriptionRepository jobDescriptionRepository;
    private final AiServiceClient aiServiceClient;
    private final SecurityAuthorizationService securityAuthorizationService;
    private final JobHiringManagerRepository jobHiringManagerRepository;
    private final UserRepository userRepository;

    public JobDescriptionService(JobDescriptionRepository jobDescriptionRepository,
                                 AiServiceClient aiServiceClient) {
        this(jobDescriptionRepository, aiServiceClient, null, null, null);
    }

    @Autowired
    public JobDescriptionService(JobDescriptionRepository jobDescriptionRepository,
                                 AiServiceClient aiServiceClient,
                                 SecurityAuthorizationService securityAuthorizationService,
                                 JobHiringManagerRepository jobHiringManagerRepository,
                                 UserRepository userRepository) {
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.aiServiceClient = aiServiceClient;
        this.securityAuthorizationService = securityAuthorizationService;
        this.jobHiringManagerRepository = jobHiringManagerRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates and persists a new job description entity.
     * Automatically assigns createdBy to the currently authenticated recruiter.
     */
    @Transactional
    public JobDescription createJob(JobDescription job) {
        if (job == null) {
            throw new IllegalArgumentException("JobDescription cannot be null.");
        }
        if (job.getTitle() == null || job.getTitle().isBlank()) {
            throw new IllegalArgumentException("Job title is required.");
        }
        if (job.getDescriptionText() == null || job.getDescriptionText().isBlank()) {
            throw new IllegalArgumentException("Job description text is required.");
        }

        if (job.getStatus() == null || job.getStatus().isBlank()) {
            job.setStatus("ACTIVE");
        }
        if (job.getRequiredSkills() == null) {
            job.setRequiredSkills(new ArrayList<>());
        }
        if (job.getMinExperienceYears() == null) {
            job.setMinExperienceYears(0);
        }

        if (job.getCreatedBy() == null && securityAuthorizationService != null) {
            job.setCreatedBy(securityAuthorizationService.getCurrentUser());
        }

        JobDescription saved = jobDescriptionRepository.save(job);
        logger.info("Created job description ID: {} with title: '{}' for user: {}",
                saved.getId(), saved.getTitle(), saved.getCreatedBy() != null ? saved.getCreatedBy().getId() : "null");
        return saved;
    }

    /**
     * DTO-facing method for creating a job description with in-transaction mapping.
     */
    @Transactional
    public JobDescriptionResponseDto createJobDto(JobDescriptionRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("JobDescriptionRequestDto cannot be null.");
        }

        JobDescription job = new JobDescription(
                dto.title(),
                dto.department(),
                dto.location(),
                dto.descriptionText(),
                dto.requiredSkills(),
                dto.minExperienceYears(),
                dto.status() != null && !dto.status().isBlank() ? dto.status() : "ACTIVE"
        );

        JobDescription saved = createJob(job);
        return JobDescriptionResponseDto.fromEntity(saved);
    }

    /**
     * Retrieves a job description entity by ID, verifying read permissions.
     */
    public JobDescription getJobById(Long id) {
        JobDescription job = jobDescriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + id));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewJob(job);
        }
        return job;
    }

    /**
     * DTO-facing method for retrieving a job description by ID.
     */
    public JobDescriptionResponseDto getJobDtoById(Long id) {
        return JobDescriptionResponseDto.fromEntity(getJobById(id));
    }

    /**
     * Retrieves all job descriptions accessible to the authenticated user:
     * - ADMIN: all jobs
     * - RECRUITER: only jobs created by recruiter
     * - HIRING_MANAGER: only jobs explicitly assigned to hiring manager
     */
    public List<JobDescription> getAllJobs() {
        if (securityAuthorizationService != null) {
            if (securityAuthorizationService.isAdmin()) {
                return jobDescriptionRepository.findAllByOrderByCreatedAtDesc();
            }
            if (securityAuthorizationService.isRecruiter()) {
                Long recruiterId = securityAuthorizationService.getCurrentUserId();
                return jobDescriptionRepository.findByCreatedByIdOrderByCreatedAtDesc(recruiterId);
            }
            if (securityAuthorizationService.isHiringManager()) {
                List<Long> assignedIds = securityAuthorizationService.getAssignedJobIdsForCurrentHiringManager();
                if (assignedIds.isEmpty()) {
                    return Collections.emptyList();
                }
                return jobDescriptionRepository.findByIdInOrderByCreatedAtDesc(assignedIds);
            }
        }
        return jobDescriptionRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * DTO-facing method for retrieving all accessible job descriptions.
     */
    public List<JobDescriptionResponseDto> getAllJobDtos() {
        return getAllJobs().stream()
                .map(JobDescriptionResponseDto::fromEntity)
                .toList();
    }

    /**
     * Retrieves active job descriptions accessible to current user.
     */
    public List<JobDescription> getActiveJobs() {
        return getJobsByDepartmentAndStatus(null, "ACTIVE");
    }

    /**
     * DTO-facing method for retrieving active job descriptions.
     */
    public List<JobDescriptionResponseDto> getActiveJobDtos() {
        return getActiveJobs().stream()
                .map(JobDescriptionResponseDto::fromEntity)
                .toList();
    }

    /**
     * Retrieves job descriptions filtered by department and status respecting ownership.
     */
    public List<JobDescription> getJobsByDepartmentAndStatus(String department, String status) {
        String targetStatus = (status != null && !status.isBlank()) ? status.trim().toUpperCase() : null;
        String targetDept = (department != null && !department.isBlank()) ? department.trim() : null;

        if (securityAuthorizationService != null) {
            if (securityAuthorizationService.isRecruiter()) {
                Long recruiterId = securityAuthorizationService.getCurrentUserId();
                if (targetDept != null && targetStatus != null) {
                    return jobDescriptionRepository.findByCreatedByIdAndDepartmentIgnoreCaseAndStatus(recruiterId, targetDept, targetStatus);
                } else if (targetStatus != null) {
                    return jobDescriptionRepository.findByCreatedByIdAndStatus(recruiterId, targetStatus);
                } else if (targetDept != null) {
                    return jobDescriptionRepository.findByCreatedByIdOrderByCreatedAtDesc(recruiterId).stream()
                            .filter(j -> targetDept.equalsIgnoreCase(j.getDepartment()))
                            .toList();
                } else {
                    return jobDescriptionRepository.findByCreatedByIdOrderByCreatedAtDesc(recruiterId);
                }
            } else if (securityAuthorizationService.isHiringManager()) {
                List<Long> assignedIds = securityAuthorizationService.getAssignedJobIdsForCurrentHiringManager();
                if (assignedIds.isEmpty()) {
                    return Collections.emptyList();
                }
                List<JobDescription> assignedJobs = jobDescriptionRepository.findByIdInOrderByCreatedAtDesc(assignedIds);
                return assignedJobs.stream()
                        .filter(j -> targetStatus == null || targetStatus.equalsIgnoreCase(j.getStatus()))
                        .filter(j -> targetDept == null || (j.getDepartment() != null && targetDept.equalsIgnoreCase(j.getDepartment())))
                        .toList();
            }
        }

        // Fallback for ADMIN or unauthenticated context
        if (targetDept != null) {
            return jobDescriptionRepository.findByDepartmentIgnoreCaseAndStatus(targetDept, targetStatus != null ? targetStatus : "ACTIVE");
        }
        if (targetStatus != null) {
            return jobDescriptionRepository.findByStatus(targetStatus);
        }
        return jobDescriptionRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * DTO-facing query method supporting optional department and status filters.
     */
    public List<JobDescriptionResponseDto> getJobDtos(String department, String status) {
        return getJobsByDepartmentAndStatus(department, status).stream()
                .map(JobDescriptionResponseDto::fromEntity)
                .toList();
    }

    /**
     * Searches jobs by title keyword respecting user ownership/assignment.
     */
    public List<JobDescription> searchJobs(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return getAllJobs();
        }
        String term = keyword.trim();

        if (securityAuthorizationService != null) {
            if (securityAuthorizationService.isRecruiter()) {
                Long recruiterId = securityAuthorizationService.getCurrentUserId();
                return jobDescriptionRepository.findByCreatedByIdAndTitleContainingIgnoreCase(recruiterId, term);
            }
            if (securityAuthorizationService.isHiringManager()) {
                List<Long> assignedIds = securityAuthorizationService.getAssignedJobIdsForCurrentHiringManager();
                if (assignedIds.isEmpty()) {
                    return Collections.emptyList();
                }
                return jobDescriptionRepository.findByIdInAndTitleContainingIgnoreCase(assignedIds, term);
            }
        }

        return jobDescriptionRepository.findByTitleContainingIgnoreCase(term);
    }

    /**
     * DTO-facing method for searching jobs by keyword.
     */
    public List<JobDescriptionResponseDto> searchJobDtos(String keyword) {
        return searchJobs(keyword).stream()
                .map(JobDescriptionResponseDto::fromEntity)
                .toList();
    }

    /**
     * Updates an existing job description entity, verifying ownership before mutation.
     */
    @Transactional
    public JobDescription updateJob(Long id, JobDescription updated) {
        JobDescription existing = jobDescriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + id));

        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageJob(existing);
        }

        if (updated.getTitle() != null && !updated.getTitle().isBlank()) {
            existing.setTitle(updated.getTitle().trim());
        }
        if (updated.getDepartment() != null) {
            existing.setDepartment(updated.getDepartment().trim());
        }
        if (updated.getLocation() != null) {
            existing.setLocation(updated.getLocation().trim());
        }
        if (updated.getDescriptionText() != null && !updated.getDescriptionText().isBlank()) {
            existing.setDescriptionText(updated.getDescriptionText().trim());
        }
        if (updated.getRequiredSkills() != null) {
            existing.setRequiredSkills(updated.getRequiredSkills());
        }
        if (updated.getMinExperienceYears() != null) {
            existing.setMinExperienceYears(updated.getMinExperienceYears());
        }
        if (updated.getStatus() != null && !updated.getStatus().isBlank()) {
            existing.setStatus(updated.getStatus().trim().toUpperCase());
        }

        JobDescription saved = jobDescriptionRepository.save(existing);
        logger.info("Updated job description ID: {}", saved.getId());
        return saved;
    }

    /**
     * DTO-facing method for updating a job description.
     */
    @Transactional
    public JobDescriptionResponseDto updateJobDto(Long id, JobDescriptionRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("JobDescriptionRequestDto cannot be null.");
        }
        JobDescription details = new JobDescription(
                dto.title(),
                dto.department(),
                dto.location(),
                dto.descriptionText(),
                dto.requiredSkills(),
                dto.minExperienceYears(),
                dto.status()
        );
        JobDescription updated = updateJob(id, details);
        return JobDescriptionResponseDto.fromEntity(updated);
    }

    /**
     * Updates the status of a job description, verifying ownership before mutation.
     */
    @Transactional
    public JobDescription updateJobStatus(Long id, String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Status cannot be blank.");
        }
        JobDescription existing = jobDescriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + id));

        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageJob(existing);
        }

        existing.setStatus(status.trim().toUpperCase());
        JobDescription saved = jobDescriptionRepository.save(existing);
        logger.info("Updated status of job ID {} to '{}'", id, saved.getStatus());
        return saved;
    }

    /**
     * DTO-facing method for updating job status.
     */
    @Transactional
    public JobDescriptionResponseDto updateJobStatusDto(Long id, String status) {
        JobDescription updated = updateJobStatus(id, status);
        return JobDescriptionResponseDto.fromEntity(updated);
    }

    /**
     * Deletes a job description by ID, verifying ownership before mutation.
     */
    @Transactional
    public void deleteJob(Long id) {
        JobDescription existing = jobDescriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + id));

        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageJob(existing);
        }

        if (jobHiringManagerRepository != null) {
            jobHiringManagerRepository.deleteByJobDescriptionId(id);
        }
        jobDescriptionRepository.deleteById(id);
        logger.info("Deleted job description ID: {}", id);
    }

    // =========================================================================
    // Hiring Manager Job Assignments Management
    // =========================================================================

    /**
     * Assigns a user with ROLE_HIRING_MANAGER to a job posting.
     * Permitted only to ADMIN or the owning RECRUITER.
     */
    @Transactional
    public JobAssignmentResponseDto assignHiringManager(Long jobId, Long hiringManagerId) {
        if (jobId == null || hiringManagerId == null) {
            throw new IllegalArgumentException("Job ID and Hiring Manager ID cannot be null.");
        }

        JobDescription job = jobDescriptionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));

        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageJob(job);
        }

        if (userRepository == null || jobHiringManagerRepository == null) {
            throw new IllegalStateException("Required assignment repositories are not configured.");
        }

        User hiringManager = userRepository.findById(hiringManagerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + hiringManagerId));

        if (!Boolean.TRUE.equals(hiringManager.getIsActive())) {
            throw new IllegalArgumentException("Cannot assign inactive user as hiring manager");
        }

        boolean isHm = hiringManager.getRoles().stream()
                .anyMatch(r -> "ROLE_HIRING_MANAGER".equals(r.getName()));
        if (!isHm) {
            throw new IllegalArgumentException("Assigned user must possess ROLE_HIRING_MANAGER");
        }

        if (jobHiringManagerRepository.existsByJobDescriptionIdAndHiringManagerId(jobId, hiringManagerId)) {
            JobHiringManager existing = jobHiringManagerRepository
                    .findByJobDescriptionIdAndHiringManagerId(jobId, hiringManagerId).orElseThrow();
            return JobAssignmentResponseDto.fromEntity(existing);
        }

        User assigner = (securityAuthorizationService != null) ? securityAuthorizationService.getCurrentUser() : null;
        JobHiringManager assignment = new JobHiringManager(job, hiringManager, assigner);
        JobHiringManager saved = jobHiringManagerRepository.save(assignment);
        logger.info("Assigned hiring manager {} to job {} by user {}",
                hiringManagerId, jobId, assigner != null ? assigner.getId() : "unknown");
        return JobAssignmentResponseDto.fromEntity(saved);
    }

    /**
     * Removes an assigned hiring manager from a job posting.
     * Permitted only to ADMIN or the owning RECRUITER.
     */
    @Transactional
    public void removeHiringManager(Long jobId, Long hiringManagerId) {
        if (jobId == null || hiringManagerId == null) {
            throw new IllegalArgumentException("Job ID and Hiring Manager ID cannot be null.");
        }

        JobDescription job = jobDescriptionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));

        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageJob(job);
        }

        if (jobHiringManagerRepository != null) {
            jobHiringManagerRepository.deleteByJobDescriptionIdAndHiringManagerId(jobId, hiringManagerId);
            logger.info("Removed hiring manager {} assignment from job {}", hiringManagerId, jobId);
        }
    }

    /**
     * Lists all hiring managers assigned to a job posting.
     * Accessible by ADMIN, owning RECRUITER, or assigned HIRING_MANAGER.
     */
    public List<JobAssignmentResponseDto> getAssignedHiringManagers(Long jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null.");
        }

        JobDescription job = jobDescriptionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job description not found with ID: " + jobId));

        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewJob(job);
        }

        if (jobHiringManagerRepository == null) {
            return Collections.emptyList();
        }

        return jobHiringManagerRepository.findByJobDescriptionId(jobId).stream()
                .map(JobAssignmentResponseDto::fromEntity)
                .toList();
    }

    /**
     * Extracts normalized required skills from raw job description text via the Python AI microservice.
     */
    public JobSkillExtractionResponseDto extractSkillsFromJobText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Job description text cannot be blank.");
        }
        if (text.length() > 50000) {
            throw new IllegalArgumentException("Job description text must not exceed 50,000 characters.");
        }

        logger.info("Extracting skills from job description text (length: {} chars)", text.length());
        AiServiceClient.ResumeParseResponse parseResponse = aiServiceClient.parseText(text, "job_description.txt");

        if (!parseResponse.success()) {
            logger.warn("AI service skill extraction reported failure: {}", parseResponse.error_message());
            return new JobSkillExtractionResponseDto(
                    Collections.emptyList(),
                    0,
                    false,
                    parseResponse.error_message() != null ? parseResponse.error_message() : "Failed to extract skills from text."
            );
        }

        List<String> skills = (parseResponse.extracted_skills() != null)
                ? parseResponse.extracted_skills().stream()
                    .map(AiServiceClient.ExtractedSkill::skill_name)
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList()
                : Collections.emptyList();

        logger.info("Successfully extracted {} distinct skills (word count: {})", skills.size(), parseResponse.word_count());
        return new JobSkillExtractionResponseDto(
                skills,
                parseResponse.word_count(),
                true,
                "Skills extracted successfully."
        );
    }
}

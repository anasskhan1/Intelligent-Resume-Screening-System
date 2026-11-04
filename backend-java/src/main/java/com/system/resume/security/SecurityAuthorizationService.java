package com.system.resume.security;

import com.system.resume.model.Candidate;
import com.system.resume.model.CandidateRanking;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeBatch;
import com.system.resume.model.ScreeningResult;
import com.system.resume.model.User;
import com.system.resume.repository.JobHiringManagerRepository;
import com.system.resume.repository.ScreeningResultRepository;
import com.system.resume.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Centralized authorization and data ownership service for Phase 7 Step 2.
 *
 * Enforces database-backed recruiter data ownership and explicit hiring manager
 * job assignments, preventing Insecure Direct Object Reference (IDOR) attacks.
 *
 * Rules:
 * 1. ROLE_ADMIN: Full platform access across all recruiters and jobs.
 * 2. ROLE_RECRUITER: Can create jobs and batches; can only view, update, delete, screen,
 *    and rank resources owned by their account (where createdBy / uploadedBy == recruiterId).
 * 3. ROLE_HIRING_MANAGER: Strictly READ-ONLY access exclusively to jobs explicitly assigned via
 *    job_hiring_managers, including rankings, permitted candidates, and screening results for those assigned jobs.
 *    Denies job creation, updates, deletes, batch uploads, screening execution, and review decisions.
 * 4. Historical NULL-owner data: Accessible strictly to ROLE_ADMIN. Missing ownership
 *    never grants access to recruiters or hiring managers.
 */
@Service
public class SecurityAuthorizationService {

    private static final Logger logger = LoggerFactory.getLogger(SecurityAuthorizationService.class);

    private final UserRepository userRepository;
    private final JobHiringManagerRepository jobHiringManagerRepository;
    private final ScreeningResultRepository screeningResultRepository;

    @Autowired
    public SecurityAuthorizationService(UserRepository userRepository,
                                        JobHiringManagerRepository jobHiringManagerRepository,
                                        ScreeningResultRepository screeningResultRepository) {
        this.userRepository = userRepository;
        this.jobHiringManagerRepository = jobHiringManagerRepository;
        this.screeningResultRepository = screeningResultRepository;
    }

    public SecurityAuthorizationService(UserRepository userRepository,
                                        JobHiringManagerRepository jobHiringManagerRepository) {
        this(userRepository, jobHiringManagerRepository, null);
    }

    /**
     * Resolves the currently authenticated User entity from the Spring Security context.
     *
     * @return User entity
     * @throws AccessDeniedException if not authenticated
     */
    public User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Authentication required to access this resource");
        }

        Object principal = auth.getPrincipal();
        if (principal instanceof CustomUserDetails cud) {
            return cud.getUser();
        }
        if (principal instanceof User user) {
            return user;
        }

        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Authenticated user account not found for: " + email));
    }

    /**
     * Returns the current authenticated user's ID.
     */
    public Long getCurrentUserId() {
        return getCurrentUser().getId();
    }

    /**
     * Checks if the currently authenticated user possesses ROLE_ADMIN.
     */
    public boolean isAdmin() {
        return hasRole("ROLE_ADMIN");
    }

    /**
     * Checks if the currently authenticated user possesses ROLE_RECRUITER.
     */
    public boolean isRecruiter() {
        return hasRole("ROLE_RECRUITER");
    }

    /**
     * Checks if the currently authenticated user possesses ROLE_HIRING_MANAGER.
     */
    public boolean isHiringManager() {
        return hasRole("ROLE_HIRING_MANAGER");
    }

    private boolean hasRole(String roleName) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities == null) {
            return false;
        }
        return authorities.stream()
                .anyMatch(a -> Objects.equals(a.getAuthority(), roleName));
    }

    // =========================================================================
    // Job Description Ownership & Access Enforcement
    // =========================================================================

    /**
     * Validates that the current user has permission to read a job description.
     *
     * - ADMIN: unrestricted.
     * - RECRUITER: must be the creator (job.createdBy.id == currentUserId).
     * - HIRING_MANAGER: must be explicitly assigned via job_hiring_managers.
     * - Unowned (job.createdBy == null): ADMIN only.
     */
    public void validateCanViewJob(JobDescription job) {
        if (job == null) {
            throw new IllegalArgumentException("JobDescription cannot be null.");
        }
        if (isAdmin()) {
            return;
        }

        Long currentUserId = getCurrentUserId();

        // Historical unowned records: strictly ADMIN only
        if (job.getCreatedBy() == null) {
            logger.warn("Access denied for user {} to unowned historical job {}", currentUserId, job.getId());
            throw new AccessDeniedException("Access denied: Unowned historical job is only accessible to administrators");
        }

        if (isRecruiter()) {
            if (job.getCreatedBy().getId().equals(currentUserId)) {
                return;
            }
            logger.warn("IDOR BLOCKED: Recruiter {} attempted to access job {} owned by user {}",
                    currentUserId, job.getId(), job.getCreatedBy().getId());
            throw new AccessDeniedException("Access denied: You do not own this job description");
        }

        if (isHiringManager()) {
            if (jobHiringManagerRepository.existsByJobDescriptionIdAndHiringManagerId(job.getId(), currentUserId)) {
                return;
            }
            logger.warn("IDOR BLOCKED: Hiring manager {} attempted to access unassigned job {}",
                    currentUserId, job.getId());
            throw new AccessDeniedException("Access denied: You are not assigned to this job description");
        }

        throw new AccessDeniedException("Access denied: Insufficient permissions to view job description");
    }

    /**
     * Validates that the current user has permission to mutate a job description
     * (update, delete, change status, assign/unassign hiring managers).
     *
     * - ADMIN: unrestricted.
     * - RECRUITER: must be the creator (job.createdBy.id == currentUserId).
     * - HIRING_MANAGER: always forbidden (read-only role).
     * - Unowned: ADMIN only.
     */
    public void validateCanManageJob(JobDescription job) {
        if (job == null) {
            throw new IllegalArgumentException("JobDescription cannot be null.");
        }
        if (isAdmin()) {
            return;
        }

        Long currentUserId = getCurrentUserId();

        if (isHiringManager()) {
            logger.warn("Mutation blocked: Hiring manager {} attempted to modify job {}", currentUserId, job.getId());
            throw new AccessDeniedException("Access denied: Hiring managers cannot modify job descriptions");
        }

        if (job.getCreatedBy() == null) {
            logger.warn("Mutation blocked: User {} attempted to modify unowned historical job {}", currentUserId, job.getId());
            throw new AccessDeniedException("Access denied: Unowned historical job can only be modified by administrators");
        }

        if (isRecruiter()) {
            if (job.getCreatedBy().getId().equals(currentUserId)) {
                return;
            }
            logger.warn("IDOR BLOCKED: Recruiter {} attempted to mutate job {} owned by user {}",
                    currentUserId, job.getId(), job.getCreatedBy().getId());
            throw new AccessDeniedException("Access denied: You do not own this job description");
        }

        throw new AccessDeniedException("Access denied: Insufficient permissions to manage job description");
    }

    // =========================================================================
    // Resume Batch Ownership Enforcement
    // =========================================================================

    /**
     * Validates that the current user has permission to view a resume batch.
     *
     * - ADMIN: unrestricted.
     * - RECRUITER: must be the uploader (batch.uploadedBy.id == currentUserId).
     * - HIRING_MANAGER: forbidden (cannot view batch uploads).
     * - Unowned: ADMIN only.
     */
    public void validateCanViewBatch(ResumeBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("ResumeBatch cannot be null.");
        }
        if (isAdmin()) {
            return;
        }

        Long currentUserId = getCurrentUserId();

        if (isHiringManager()) {
            throw new AccessDeniedException("Access denied: Hiring managers cannot view resume batches");
        }

        if (batch.getUploadedBy() == null) {
            logger.warn("Access denied for user {} to unowned historical batch {}", currentUserId, batch.getId());
            throw new AccessDeniedException("Access denied: Unowned historical batch is only accessible to administrators");
        }

        if (isRecruiter()) {
            if (batch.getUploadedBy().getId().equals(currentUserId)) {
                return;
            }
            logger.warn("IDOR BLOCKED: Recruiter {} attempted to access batch {} owned by user {}",
                    currentUserId, batch.getId(), batch.getUploadedBy().getId());
            throw new AccessDeniedException("Access denied: You do not own this resume batch");
        }

        throw new AccessDeniedException("Access denied: Insufficient permissions to view resume batch");
    }

    // =========================================================================
    // Candidate Ranking & Leaderboard Access Enforcement
    // =========================================================================

    /**
     * Validates that the current user has permission to view a candidate ranking or leaderboard.
     *
     * Governed by job visibility rules (ADMIN, owning RECRUITER, or assigned HIRING_MANAGER).
     */
    public void validateCanViewRanking(CandidateRanking ranking) {
        if (ranking == null) {
            throw new IllegalArgumentException("CandidateRanking cannot be null.");
        }
        validateCanViewJob(ranking.getJobDescription());
    }

    /**
     * Validates that the current user has permission to update review decisions / feedback.
     *
     * Governed by job management rules (ADMIN or owning RECRUITER). HIRING_MANAGER is strictly forbidden.
     */
    public void validateCanReviewRanking(CandidateRanking ranking) {
        if (ranking == null) {
            throw new IllegalArgumentException("CandidateRanking cannot be null.");
        }
        if (isHiringManager()) {
            logger.warn("Review blocked: Hiring manager {} attempted to update review status on ranking {}",
                    getCurrentUserId(), ranking.getId());
            throw new AccessDeniedException("Access denied: Hiring managers cannot update review status or notes");
        }
        validateCanManageJob(ranking.getJobDescription());
    }

    // =========================================================================
    // Candidate Access Enforcement
    // =========================================================================

    /**
     * Validates that the current user has permission to view a candidate profile.
     *
     * - ADMIN: unrestricted.
     * - RECRUITER: permitted if candidate is associated with at least one job owned by recruiter,
     *   or if candidate is unattached to any jobs yet.
     * - HIRING_MANAGER: permitted if candidate is associated with at least one job assigned to the manager.
     */
    public void validateCanViewCandidate(Candidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate cannot be null.");
        }
        if (isAdmin()) {
            return;
        }

        Long currentUserId = getCurrentUserId();
        List<JobDescription> associatedJobs = (screeningResultRepository != null)
                ? screeningResultRepository.findJobDescriptionsByCandidateId(candidate.getId())
                : Collections.emptyList();

        if (associatedJobs.isEmpty()) {
            if (isRecruiter()) {
                return; // Newly created candidate without job associations yet
            }
            if (isHiringManager()) {
                logger.warn("IDOR BLOCKED: Hiring manager {} attempted to view unassociated candidate {}",
                        currentUserId, candidate.getId());
                throw new AccessDeniedException("Access denied: Candidate is not associated with your assigned jobs");
            }
            throw new AccessDeniedException("Access denied: Insufficient permissions to view candidate");
        }

        if (isRecruiter()) {
            boolean ownsAnyJob = associatedJobs.stream()
                    .anyMatch(j -> j.getCreatedBy() != null && j.getCreatedBy().getId().equals(currentUserId));
            if (ownsAnyJob) {
                return;
            }
            logger.warn("IDOR BLOCKED: Recruiter {} attempted to view candidate {} not associated with their jobs",
                    currentUserId, candidate.getId());
            throw new AccessDeniedException("Access denied: Candidate is not associated with your job descriptions");
        }

        if (isHiringManager()) {
            List<Long> assignedJobIds = getAssignedJobIdsForCurrentHiringManager();
            boolean assignedToAnyJob = associatedJobs.stream()
                    .anyMatch(j -> assignedJobIds.contains(j.getId()));
            if (assignedToAnyJob) {
                return;
            }
            logger.warn("IDOR BLOCKED: Hiring manager {} attempted to view candidate {} outside assigned jobs",
                    currentUserId, candidate.getId());
            throw new AccessDeniedException("Access denied: Candidate is not associated with your assigned jobs");
        }

        throw new AccessDeniedException("Access denied: Insufficient permissions to view candidate");
    }

    /**
     * Validates that the current user has permission to mutate a candidate profile
     * (update, delete).
     *
     * - ADMIN: unrestricted.
     * - RECRUITER: permitted if candidate is associated with at least one job owned by recruiter,
     *   or unattached.
     * - HIRING_MANAGER: strictly forbidden (read-only).
     */
    public void validateCanManageCandidate(Candidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate cannot be null.");
        }
        if (isAdmin()) {
            return;
        }

        Long currentUserId = getCurrentUserId();

        if (isHiringManager()) {
            logger.warn("Mutation blocked: Hiring manager {} attempted to modify candidate {}", currentUserId, candidate.getId());
            throw new AccessDeniedException("Access denied: Hiring managers cannot create, update, or delete candidates");
        }

        if (isRecruiter()) {
            List<JobDescription> associatedJobs = (screeningResultRepository != null)
                    ? screeningResultRepository.findJobDescriptionsByCandidateId(candidate.getId())
                    : Collections.emptyList();

            if (associatedJobs.isEmpty()) {
                return;
            }

            boolean ownsAnyJob = associatedJobs.stream()
                    .anyMatch(j -> j.getCreatedBy() != null && j.getCreatedBy().getId().equals(currentUserId));
            if (ownsAnyJob) {
                return;
            }
            logger.warn("IDOR BLOCKED: Recruiter {} attempted to mutate candidate {} not associated with their jobs",
                    currentUserId, candidate.getId());
            throw new AccessDeniedException("Access denied: You do not own any jobs associated with this candidate");
        }

        throw new AccessDeniedException("Access denied: Insufficient permissions to manage candidate");
    }

    // =========================================================================
    // Resume Access Enforcement
    // =========================================================================

    /**
     * Validates that the current user has permission to view a resume.
     *
     * - ADMIN: unrestricted.
     * - RECRUITER: permitted if resume is associated with a job owned by recruiter, or unattached.
     * - HIRING_MANAGER: permitted if resume is associated with a job assigned to manager.
     */
    public void validateCanViewResume(Resume resume) {
        if (resume == null) {
            throw new IllegalArgumentException("Resume cannot be null.");
        }
        if (isAdmin()) {
            return;
        }

        Long currentUserId = getCurrentUserId();
        List<JobDescription> associatedJobs = (screeningResultRepository != null)
                ? screeningResultRepository.findJobDescriptionsByResumeId(resume.getId())
                : Collections.emptyList();

        if (associatedJobs.isEmpty()) {
            if (isRecruiter()) {
                return; // Freshly uploaded resume before screening
            }
            if (isHiringManager()) {
                logger.warn("IDOR BLOCKED: Hiring manager {} attempted to view unassociated resume {}",
                        currentUserId, resume.getId());
                throw new AccessDeniedException("Access denied: Resume is not associated with your assigned jobs");
            }
            throw new AccessDeniedException("Access denied: Insufficient permissions to view resume");
        }

        if (isRecruiter()) {
            boolean ownsAnyJob = associatedJobs.stream()
                    .anyMatch(j -> j.getCreatedBy() != null && j.getCreatedBy().getId().equals(currentUserId));
            if (ownsAnyJob) {
                return;
            }
            logger.warn("IDOR BLOCKED: Recruiter {} attempted to view resume {} not associated with their jobs",
                    currentUserId, resume.getId());
            throw new AccessDeniedException("Access denied: You do not own any jobs associated with this resume");
        }

        if (isHiringManager()) {
            List<Long> assignedJobIds = getAssignedJobIdsForCurrentHiringManager();
            boolean assignedToAnyJob = associatedJobs.stream()
                    .anyMatch(j -> assignedJobIds.contains(j.getId()));
            if (assignedToAnyJob) {
                return;
            }
            logger.warn("IDOR BLOCKED: Hiring manager {} attempted to view resume {} outside assigned jobs",
                    currentUserId, resume.getId());
            throw new AccessDeniedException("Access denied: Resume is not associated with your assigned jobs");
        }

        throw new AccessDeniedException("Access denied: Insufficient permissions to view resume");
    }

    /**
     * Validates that the current user has permission to mutate a resume (upload, delete).
     *
     * - ADMIN: unrestricted.
     * - RECRUITER: permitted if resume is associated with a job owned by recruiter, or unattached.
     * - HIRING_MANAGER: strictly forbidden (read-only).
     */
    public void validateCanManageResume(Resume resume) {
        if (resume == null) {
            throw new IllegalArgumentException("Resume cannot be null.");
        }
        if (isAdmin()) {
            return;
        }

        Long currentUserId = getCurrentUserId();

        if (isHiringManager()) {
            logger.warn("Mutation blocked: Hiring manager {} attempted to mutate resume {}", currentUserId, resume.getId());
            throw new AccessDeniedException("Access denied: Hiring managers cannot upload or delete resumes");
        }

        if (isRecruiter()) {
            List<JobDescription> associatedJobs = (screeningResultRepository != null)
                    ? screeningResultRepository.findJobDescriptionsByResumeId(resume.getId())
                    : Collections.emptyList();

            if (associatedJobs.isEmpty()) {
                return;
            }

            boolean ownsAnyJob = associatedJobs.stream()
                    .anyMatch(j -> j.getCreatedBy() != null && j.getCreatedBy().getId().equals(currentUserId));
            if (ownsAnyJob) {
                return;
            }
            logger.warn("IDOR BLOCKED: Recruiter {} attempted to mutate resume {} owned by another recruiter",
                    currentUserId, resume.getId());
            throw new AccessDeniedException("Access denied: You do not own any jobs associated with this resume");
        }

        throw new AccessDeniedException("Access denied: Insufficient permissions to manage resume");
    }

    // =========================================================================
    // Screening Result Access Enforcement
    // =========================================================================

    /**
     * Validates that the current user has permission to view a screening result.
     *
     * Governed by job visibility rules (ADMIN, owning RECRUITER, or assigned HIRING_MANAGER).
     */
    public void validateCanViewScreeningResult(ScreeningResult result) {
        if (result == null) {
            throw new IllegalArgumentException("ScreeningResult cannot be null.");
        }
        validateCanViewJob(result.getJobDescription());
    }

    /**
     * Validates that the current user has permission to trigger a screening evaluation.
     *
     * Governed by job management rules (ADMIN or owning RECRUITER). HIRING_MANAGER is forbidden.
     */
    public void validateCanPerformScreening(JobDescription job) {
        validateCanManageJob(job);
    }

    // =========================================================================
    // Hiring Manager Assigned Jobs Retrieval Helper
    // =========================================================================

    /**
     * Returns the list of JobDescription IDs explicitly assigned to the currently
     * authenticated hiring manager.
     */
    public List<Long> getAssignedJobIdsForCurrentHiringManager() {
        Long currentUserId = getCurrentUserId();
        return jobHiringManagerRepository.findByHiringManagerId(currentUserId).stream()
                .map(jhm -> jhm.getJobDescription().getId())
                .toList();
    }
}


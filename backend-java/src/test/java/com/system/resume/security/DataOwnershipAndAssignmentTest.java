package com.system.resume.security;

import com.system.resume.client.AiServiceClient;
import com.system.resume.dto.JobAssignmentResponseDto;
import com.system.resume.dto.JobDescriptionRequestDto;
import com.system.resume.dto.JobDescriptionResponseDto;
import com.system.resume.model.*;
import com.system.resume.repository.JobDescriptionRepository;
import com.system.resume.repository.JobHiringManagerRepository;
import com.system.resume.repository.ScreeningResultRepository;
import com.system.resume.repository.UserRepository;
import com.system.resume.service.JobDescriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataOwnershipAndAssignmentTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JobHiringManagerRepository jobHiringManagerRepository;

    @Mock
    private JobDescriptionRepository jobDescriptionRepository;

    @Mock
    private ScreeningResultRepository screeningResultRepository;

    @Mock
    private AiServiceClient aiServiceClient;

    private SecurityAuthorizationService securityAuthorizationService;
    private JobDescriptionService jobDescriptionService;

    private User adminUser;
    private User recruiterA;
    private User recruiterB;
    private User hiringManager;

    @BeforeEach
    void setUp() {
        securityAuthorizationService = new SecurityAuthorizationService(userRepository, jobHiringManagerRepository, screeningResultRepository);
        jobDescriptionService = new JobDescriptionService(
                jobDescriptionRepository,
                aiServiceClient,
                securityAuthorizationService,
                jobHiringManagerRepository,
                userRepository
        );

        adminUser = new User("admin@company.com", "hash", "Admin", "User", true);
        adminUser.setId(1L);
        adminUser.getRoles().add(new Role(1L, "ROLE_ADMIN", "Administrator", LocalDateTime.now()));

        recruiterA = new User("recruiterA@company.com", "hash", "Recruiter", "A", true);
        recruiterA.setId(2L);
        recruiterA.getRoles().add(new Role(2L, "ROLE_RECRUITER", "Recruiter", LocalDateTime.now()));

        recruiterB = new User("recruiterB@company.com", "hash", "Recruiter", "B", true);
        recruiterB.setId(3L);
        recruiterB.getRoles().add(new Role(2L, "ROLE_RECRUITER", "Recruiter", LocalDateTime.now()));

        hiringManager = new User("hm@company.com", "hash", "Hiring", "Manager", true);
        hiringManager.setId(4L);
        hiringManager.getRoles().add(new Role(3L, "ROLE_HIRING_MANAGER", "Hiring Manager", LocalDateTime.now()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User user) {
        CustomUserDetails userDetails = new CustomUserDetails(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // =========================================================================
    // 1. Recruiter Ownership & IDOR Protection Tests
    // =========================================================================

    @Test
    @DisplayName("Recruiter A can view and modify their own job description")
    void testRecruiter_CanViewAndManageOwnJob() {
        authenticateAs(recruiterA);

        JobDescription jobA = new JobDescription("Job A", "Tech", "Remote", "Desc A", List.of("Java"), 2, "ACTIVE");
        jobA.setId(101L);
        jobA.setCreatedBy(recruiterA);

        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewJob(jobA));
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanManageJob(jobA));
    }

    @Test
    @DisplayName("Recruiter A is DENIED access when attempting IDOR on Recruiter B's job")
    void testRecruiter_IdorAccessOnOtherRecruiterJob_ThrowsAccessDenied() {
        authenticateAs(recruiterA);

        JobDescription jobB = new JobDescription("Job B", "Tech", "Remote", "Desc B", List.of("Python"), 3, "ACTIVE");
        jobB.setId(102L);
        jobB.setCreatedBy(recruiterB);

        AccessDeniedException exView = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewJob(jobB));
        assertTrue(exView.getMessage().contains("do not own"));

        AccessDeniedException exManage = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanManageJob(jobB));
        assertTrue(exManage.getMessage().contains("do not own"));
    }

    @Test
    @DisplayName("Recruiter A is DENIED access to an unowned historical job (createdBy == null)")
    void testRecruiter_UnownedHistoricalJob_ThrowsAccessDenied() {
        authenticateAs(recruiterA);

        JobDescription historicalJob = new JobDescription("Historical", "Tech", "Remote", "Desc", List.of(), 1, "ACTIVE");
        historicalJob.setId(999L);
        historicalJob.setCreatedBy(null);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewJob(historicalJob));
        assertTrue(ex.getMessage().contains("Unowned historical job is only accessible to administrators"));
    }

    @Test
    @DisplayName("Admin has full access to unowned historical records")
    void testAdmin_CanAccessUnownedHistoricalJob() {
        authenticateAs(adminUser);

        JobDescription historicalJob = new JobDescription("Historical", "Tech", "Remote", "Desc", List.of(), 1, "ACTIVE");
        historicalJob.setId(999L);
        historicalJob.setCreatedBy(null);

        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewJob(historicalJob));
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanManageJob(historicalJob));
    }

    @Test
    @DisplayName("JobDescriptionService.createJob automatically sets createdBy to current authenticated user")
    void testJobDescriptionService_CreateJob_AssignsCreatedBy() {
        authenticateAs(recruiterA);

        JobDescriptionRequestDto requestDto = new JobDescriptionRequestDto(
                "New Posting", "Product", "Office", "Detailed description", List.of("Agile"), 3, "ACTIVE"
        );

        when(jobDescriptionRepository.save(any(JobDescription.class))).thenAnswer(invocation -> {
            JobDescription j = invocation.getArgument(0);
            j.setId(200L);
            return j;
        });

        JobDescriptionResponseDto response = jobDescriptionService.createJobDto(requestDto);

        assertNotNull(response);
        assertEquals(200L, response.id());
        assertEquals(recruiterA.getId(), response.createdByUserId());
    }

    // =========================================================================
    // 2. Hiring Manager Assignment & Scoping Tests
    // =========================================================================

    @Test
    @DisplayName("Hiring manager can view assigned job but is denied unassigned job")
    void testHiringManager_AssignedVsUnassignedJobAccess() {
        authenticateAs(hiringManager);

        JobDescription assignedJob = new JobDescription("Assigned", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        assignedJob.setId(301L);
        assignedJob.setCreatedBy(recruiterA);

        JobDescription unassignedJob = new JobDescription("Unassigned", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        unassignedJob.setId(302L);
        unassignedJob.setCreatedBy(recruiterA);

        when(jobHiringManagerRepository.existsByJobDescriptionIdAndHiringManagerId(301L, hiringManager.getId()))
                .thenReturn(true);
        when(jobHiringManagerRepository.existsByJobDescriptionIdAndHiringManagerId(302L, hiringManager.getId()))
                .thenReturn(false);

        // Assigned job access granted
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewJob(assignedJob));

        // Unassigned job access denied
        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewJob(unassignedJob));
        assertTrue(ex.getMessage().contains("not assigned"));
    }

    @Test
    @DisplayName("Hiring manager is DENIED mutation operations even on assigned jobs")
    void testHiringManager_DeniedMutationOnAssignedJob() {
        authenticateAs(hiringManager);

        JobDescription assignedJob = new JobDescription("Assigned", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        assignedJob.setId(301L);
        assignedJob.setCreatedBy(recruiterA);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanManageJob(assignedJob));
        assertTrue(ex.getMessage().contains("Hiring managers cannot modify"));
    }

    @Test
    @DisplayName("Hiring manager is DENIED viewing resume batches")
    void testHiringManager_DeniedBatchAccess() {
        authenticateAs(hiringManager);

        ResumeBatch batch = new ResumeBatch("Batch 1", 5);
        batch.setId(501L);
        batch.setUploadedBy(recruiterA);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewBatch(batch));
        assertTrue(ex.getMessage().contains("Hiring managers cannot view resume batches"));
    }

    // =========================================================================
    // 3. Job Hiring Manager Assignment Management Tests
    // =========================================================================

    @Test
    @DisplayName("Owning recruiter can assign a user with ROLE_HIRING_MANAGER to their job")
    void testAssignHiringManager_Success() {
        authenticateAs(recruiterA);

        JobDescription jobA = new JobDescription("Job A", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        jobA.setId(101L);
        jobA.setCreatedBy(recruiterA);

        when(jobDescriptionRepository.findById(101L)).thenReturn(Optional.of(jobA));
        when(userRepository.findById(4L)).thenReturn(Optional.of(hiringManager));
        when(jobHiringManagerRepository.existsByJobDescriptionIdAndHiringManagerId(101L, 4L)).thenReturn(false);

        when(jobHiringManagerRepository.save(any(JobHiringManager.class))).thenAnswer(invocation -> {
            JobHiringManager jhm = invocation.getArgument(0);
            jhm.setId(1001L);
            return jhm;
        });

        JobAssignmentResponseDto response = jobDescriptionService.assignHiringManager(101L, 4L);

        assertNotNull(response);
        assertEquals(1001L, response.id());
        assertEquals(101L, response.jobId());
        assertEquals(4L, response.hiringManagerId());
        assertEquals(recruiterA.getId(), response.assignedByUserId());
    }

    @Test
    @DisplayName("Assigning user without ROLE_HIRING_MANAGER is rejected with IllegalArgumentException")
    void testAssignHiringManager_RejectsNonHiringManagerUser() {
        authenticateAs(recruiterA);

        JobDescription jobA = new JobDescription("Job A", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        jobA.setId(101L);
        jobA.setCreatedBy(recruiterA);

        User nonHmUser = new User("other@company.com", "hash", "Other", "User", true);
        nonHmUser.setId(5L);
        nonHmUser.getRoles().add(new Role(5L, "ROLE_CANDIDATE", "Candidate", LocalDateTime.now()));

        when(jobDescriptionRepository.findById(101L)).thenReturn(Optional.of(jobA));
        when(userRepository.findById(5L)).thenReturn(Optional.of(nonHmUser));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                jobDescriptionService.assignHiringManager(101L, 5L));
        assertTrue(ex.getMessage().contains("must possess ROLE_HIRING_MANAGER"));
    }

    @Test
    @DisplayName("Non-owning recruiter cannot assign hiring managers to another recruiter's job")
    void testAssignHiringManager_DeniedForNonOwnerRecruiter() {
        authenticateAs(recruiterB);

        JobDescription jobA = new JobDescription("Job A", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        jobA.setId(101L);
        jobA.setCreatedBy(recruiterA); // Owned by recruiterA

        when(jobDescriptionRepository.findById(101L)).thenReturn(Optional.of(jobA));

        assertThrows(AccessDeniedException.class, () ->
                jobDescriptionService.assignHiringManager(101L, 4L));
    }

    @Test
    @DisplayName("Assigning inactive user as hiring manager is rejected")
    void testAssignHiringManager_RejectsInactiveUser() {
        authenticateAs(recruiterA);

        JobDescription jobA = new JobDescription("Job A", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        jobA.setId(101L);
        jobA.setCreatedBy(recruiterA);

        User inactiveHm = new User("inactive_hm@company.com", "hash", "Inactive", "HM", false);
        inactiveHm.setId(6L);
        inactiveHm.setIsActive(false);
        inactiveHm.getRoles().add(new Role(3L, "ROLE_HIRING_MANAGER", "Hiring Manager", LocalDateTime.now()));

        when(jobDescriptionRepository.findById(101L)).thenReturn(Optional.of(jobA));
        when(userRepository.findById(6L)).thenReturn(Optional.of(inactiveHm));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                jobDescriptionService.assignHiringManager(101L, 6L));
        assertTrue(ex.getMessage().contains("Cannot assign inactive user"));
    }

    // =========================================================================
    // 4. Candidate Ranking & Review Permissions Tests
    // =========================================================================

    @Test
    @DisplayName("Hiring manager CANNOT update review status or notes (strictly read-only)")
    void testHiringManager_CannotUpdateReviewStatusOrNotes() {
        authenticateAs(hiringManager);

        JobDescription assignedJob = new JobDescription("Job A", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        assignedJob.setId(101L);
        assignedJob.setCreatedBy(recruiterA);

        CandidateRanking ranking = new CandidateRanking();
        ranking.setId(1L);
        ranking.setJobDescription(assignedJob);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanReviewRanking(ranking));
        assertTrue(ex.getMessage().contains("Hiring managers cannot update review status or notes"));
    }

    @Test
    @DisplayName("Recruiter can review only rankings for their own jobs")
    void testRecruiter_CanReviewOnlyOwnRankings() {
        authenticateAs(recruiterA);

        JobDescription ownJob = new JobDescription("Job A", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        ownJob.setId(101L);
        ownJob.setCreatedBy(recruiterA);

        JobDescription otherJob = new JobDescription("Job B", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        otherJob.setId(102L);
        otherJob.setCreatedBy(recruiterB);

        CandidateRanking ownRanking = new CandidateRanking();
        ownRanking.setId(1L);
        ownRanking.setJobDescription(ownJob);

        CandidateRanking otherRanking = new CandidateRanking();
        otherRanking.setId(2L);
        otherRanking.setJobDescription(otherJob);

        // Permitted on own job's ranking
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanReviewRanking(ownRanking));

        // Denied on another recruiter's job ranking
        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanReviewRanking(otherRanking));
        assertTrue(ex.getMessage().contains("do not own"));
    }

    @Test
    @DisplayName("Historical NULL-owner ranking is DENIED to recruiter and hiring manager, permitted to ADMIN")
    void testHistoricalNullOwnerRanking_AdminOnly() {
        JobDescription historicalJob = new JobDescription("Old Job", "Tech", "Remote", "Desc", List.of(), 1, "ACTIVE");
        historicalJob.setId(999L);
        historicalJob.setCreatedBy(null);

        CandidateRanking ranking = new CandidateRanking();
        ranking.setId(99L);
        ranking.setJobDescription(historicalJob);

        // Recruiter denied
        authenticateAs(recruiterA);
        assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanReviewRanking(ranking));
        assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewRanking(ranking));

        // Hiring Manager denied
        authenticateAs(hiringManager);
        assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanReviewRanking(ranking));
        assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewRanking(ranking));

        // Admin permitted
        authenticateAs(adminUser);
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanReviewRanking(ranking));
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewRanking(ranking));
    }

    // =========================================================================
    // 5. Candidate & Resume Access Scoping Tests
    // =========================================================================

    @Test
    @DisplayName("Candidate access is scoped by associated job ownership and assignments")
    void testCandidate_AccessScopingByAssociatedJob() {
        JobDescription jobA = new JobDescription("Job A", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        jobA.setId(101L);
        jobA.setCreatedBy(recruiterA);

        Candidate candidate = new Candidate("Alice", "Smith", "alice@test.com", "123456");
        candidate.setId(50L);

        when(screeningResultRepository.findJobDescriptionsByCandidateId(50L))
                .thenReturn(List.of(jobA));

        // Recruiter A owns Job A -> can view
        authenticateAs(recruiterA);
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewCandidate(candidate));

        // Recruiter B does not own Job A -> IDOR blocked
        authenticateAs(recruiterB);
        AccessDeniedException exRecruiter = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewCandidate(candidate));
        assertTrue(exRecruiter.getMessage().contains("not associated with your job descriptions"));

        // Hiring manager unassigned to Job A -> blocked
        authenticateAs(hiringManager);
        when(jobHiringManagerRepository.findByHiringManagerId(hiringManager.getId()))
                .thenReturn(List.of());
        AccessDeniedException exHm = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewCandidate(candidate));
        assertTrue(exHm.getMessage().contains("not associated with your assigned jobs"));

        // Hiring manager assigned to Job A -> view permitted
        JobHiringManager assignment = new JobHiringManager(jobA, hiringManager, recruiterA);
        when(jobHiringManagerRepository.findByHiringManagerId(hiringManager.getId()))
                .thenReturn(List.of(assignment));
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewCandidate(candidate));

        // Hiring manager mutation blocked even when assigned
        AccessDeniedException exHmMutate = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanManageCandidate(candidate));
        assertTrue(exHmMutate.getMessage().contains("Hiring managers cannot create, update, or delete candidates"));

        // Admin has full access to view and manage candidate
        authenticateAs(adminUser);
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewCandidate(candidate));
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanManageCandidate(candidate));
    }

    @Test
    @DisplayName("Resume access is scoped by associated job ownership and assignments")
    void testResume_AccessScopingByAssociatedJob() {
        JobDescription jobA = new JobDescription("Job A", "Tech", "Remote", "Desc", List.of(), 2, "ACTIVE");
        jobA.setId(101L);
        jobA.setCreatedBy(recruiterA);

        Candidate candidate = new Candidate("Bob", "Jones", "bob@test.com", "987654");
        candidate.setId(51L);

        Resume resume = new Resume(candidate, "resume.pdf", "resumes/resume.pdf", "application/pdf", 1024L, "Text", "PARSED");
        resume.setId(70L);

        when(screeningResultRepository.findJobDescriptionsByResumeId(70L))
                .thenReturn(List.of(jobA));

        // Recruiter A owns Job A -> view permitted
        authenticateAs(recruiterA);
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewResume(resume));

        // Recruiter B does not own Job A -> IDOR blocked
        authenticateAs(recruiterB);
        AccessDeniedException exRecruiter = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewResume(resume));
        assertTrue(exRecruiter.getMessage().contains("do not own any jobs associated with this resume"));

        // Hiring manager unassigned -> blocked
        authenticateAs(hiringManager);
        when(jobHiringManagerRepository.findByHiringManagerId(hiringManager.getId()))
                .thenReturn(List.of());
        AccessDeniedException exHm = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanViewResume(resume));
        assertTrue(exHm.getMessage().contains("Resume is not associated with your assigned jobs"));

        // Hiring manager mutation blocked
        AccessDeniedException exHmMutate = assertThrows(AccessDeniedException.class, () ->
                securityAuthorizationService.validateCanManageResume(resume));
        assertTrue(exHmMutate.getMessage().contains("Hiring managers cannot upload or delete resumes"));

        // Admin has full view and manage access
        authenticateAs(adminUser);
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanViewResume(resume));
        assertDoesNotThrow(() -> securityAuthorizationService.validateCanManageResume(resume));
    }
}

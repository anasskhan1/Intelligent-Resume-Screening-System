package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.client.AiServiceClient.MatchRequest;
import com.system.resume.client.AiServiceClient.MatchResponse;
import com.system.resume.model.Candidate;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeBatch;
import com.system.resume.model.ResumeSkill;
import com.system.resume.model.ScreeningResult;
import com.system.resume.model.User;
import com.system.resume.repository.JobDescriptionRepository;
import com.system.resume.repository.ResumeBatchRepository;
import com.system.resume.repository.ResumeRepository;
import com.system.resume.repository.ResumeSkillRepository;
import com.system.resume.repository.ScreeningResultRepository;
import com.system.resume.security.SecurityAuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScreeningServiceTest {

    @Mock
    private ScreeningResultRepository screeningResultRepository;

    @Mock
    private JobDescriptionRepository jobDescriptionRepository;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeSkillRepository resumeSkillRepository;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private SecurityAuthorizationService securityAuthorizationService;

    @Mock
    private ResumeBatchRepository resumeBatchRepository;

    @Mock
    private TransactionStatus transactionStatus;

    private ScreeningService screeningService;

    private JobDescription job;
    private Resume resume;
    private Candidate candidate;

    @BeforeEach
    void setUp() {
        screeningService = new ScreeningService(
                screeningResultRepository,
                jobDescriptionRepository,
                resumeRepository,
                resumeSkillRepository,
                aiServiceClient,
                transactionManager,
                securityAuthorizationService,
                resumeBatchRepository
        );

        job = new JobDescription();
        job.setId(10L);
        job.setTitle("Senior Java Developer");
        job.setDescriptionText("Looking for a Java developer with Spring Boot expertise.");
        job.setRequiredSkills(List.of("Java", "Spring Boot"));
        job.setMinExperienceYears(5);

        candidate = new Candidate("Alice", "Smith", "alice@example.com", "1234567890");
        candidate.setId(100L);

        resume = new Resume(
                candidate,
                "alice_resume.pdf",
                "/tmp/alice_resume.pdf",
                "hash123",
                "application/pdf",
                1024L,
                "Experienced Java and Spring Boot engineer with 6 years experience.",
                "PARSED"
        );
        resume.setId(200L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("User-facing screenCandidateResume enforces authorization and throws AccessDeniedException when denied")
    void testScreenCandidateResume_EnforcesAuthorization_ThrowsWhenDenied() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(jobDescriptionRepository.findById(10L)).thenReturn(Optional.of(job));
        doThrow(new AccessDeniedException("Recruiter does not own this job description"))
                .when(securityAuthorizationService).validateCanPerformScreening(job);

        assertThrows(AccessDeniedException.class, () ->
                screeningService.screenCandidateResume(10L, 200L)
        );

        verify(securityAuthorizationService, times(1)).validateCanPerformScreening(job);
        verifyNoInteractions(aiServiceClient);
        verifyNoInteractions(screeningResultRepository);
    }

    @Test
    @DisplayName("User-facing screenCandidateResume succeeds when authorized")
    void testScreenCandidateResume_SucceedsWhenAuthorized() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(jobDescriptionRepository.findById(10L)).thenReturn(Optional.of(job));
        when(resumeRepository.findById(200L)).thenReturn(Optional.of(resume));
        when(resumeSkillRepository.findByResumeId(200L)).thenReturn(Collections.emptyList());

        MatchResponse matchResponse = new MatchResponse(
                10L, 200L, 88.5, 90.0, 85.0,
                List.of("Java", "Spring Boot"),
                Collections.emptyList(),
                "Strong candidate"
        );
        when(aiServiceClient.evaluateMatch(any(MatchRequest.class))).thenReturn(matchResponse);

        ScreeningResult savedResult = new ScreeningResult(
                job, resume,
                BigDecimal.valueOf(88.5), BigDecimal.valueOf(90.0), BigDecimal.valueOf(85.0),
                List.of("Java", "Spring Boot"), Collections.emptyList(), "Strong candidate", "COMPLETED"
        );
        savedResult.setId(500L);
        when(screeningResultRepository.save(any(ScreeningResult.class))).thenReturn(savedResult);

        ScreeningResult result = screeningService.screenCandidateResume(10L, 200L);

        assertNotNull(result);
        assertEquals(500L, result.getId());
        verify(securityAuthorizationService, times(1)).validateCanPerformScreening(job);
        verify(aiServiceClient, times(1)).evaluateMatch(any(MatchRequest.class));
        verify(screeningResultRepository, times(1)).save(any(ScreeningResult.class));
    }

    @Test
    @DisplayName("Internal screenCandidateResumeForBatch succeeds without SecurityContext when batch matches target job")
    void testScreenCandidateResumeForBatch_SucceedsWithoutSecurityContext() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        SecurityContextHolder.clearContext();
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "Security context must be empty on worker thread");

        ResumeBatch batch = new ResumeBatch("Engineering Batch", 5);
        batch.setId(1L);
        batch.setJobDescription(job);

        when(jobDescriptionRepository.findById(10L)).thenReturn(Optional.of(job));
        when(resumeBatchRepository.findById(1L)).thenReturn(Optional.of(batch));
        when(resumeRepository.findById(200L)).thenReturn(Optional.of(resume));
        when(resumeSkillRepository.findByResumeId(200L)).thenReturn(Collections.emptyList());

        MatchResponse matchResponse = new MatchResponse(
                10L, 200L, 92.0, 95.0, 89.0,
                List.of("Java", "Spring Boot"),
                Collections.emptyList(),
                "Excellent match"
        );
        when(aiServiceClient.evaluateMatch(any(MatchRequest.class))).thenReturn(matchResponse);

        ScreeningResult savedResult = new ScreeningResult(
                job, resume,
                BigDecimal.valueOf(92.0), BigDecimal.valueOf(95.0), BigDecimal.valueOf(89.0),
                List.of("Java", "Spring Boot"), Collections.emptyList(), "Excellent match", "COMPLETED"
        );
        savedResult.setId(501L);
        when(screeningResultRepository.save(any(ScreeningResult.class))).thenReturn(savedResult);

        ScreeningResult result = screeningService.screenCandidateResumeForBatch(1L, 10L, 200L);

        assertNotNull(result);
        assertEquals(501L, result.getId());
        // Verify securityAuthorizationService was NEVER called on worker thread
        verifyNoInteractions(securityAuthorizationService);
        verify(resumeBatchRepository, times(1)).findById(1L);
        verify(aiServiceClient, times(1)).evaluateMatch(any(MatchRequest.class));
    }

    @Test
    @DisplayName("Internal screenCandidateResumeForBatch rejects execution when batch target job does not match jobId")
    void testScreenCandidateResumeForBatch_ThrowsWhenBatchJobMismatch() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        JobDescription differentJob = new JobDescription();
        differentJob.setId(99L);
        differentJob.setTitle("Frontend Developer");

        ResumeBatch batch = new ResumeBatch("Batch 2", 1);
        batch.setId(2L);
        batch.setJobDescription(differentJob); // Mismatch: batch was created for job 99, but request asks to screen job 10

        when(jobDescriptionRepository.findById(10L)).thenReturn(Optional.of(job));
        when(resumeBatchRepository.findById(2L)).thenReturn(Optional.of(batch));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                screeningService.screenCandidateResumeForBatch(2L, 10L, 200L)
        );

        assertTrue(ex.getMessage().contains("Security violation: Batch ID 2 is not authorized for target Job ID 10"));
        verifyNoInteractions(aiServiceClient);
        verifyNoInteractions(screeningResultRepository);
    }

    @Test
    @DisplayName("Internal screenCandidateResumeForBatch rejects null batchId")
    void testScreenCandidateResumeForBatch_ThrowsWhenBatchIdIsNull() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                screeningService.screenCandidateResumeForBatch(null, 10L, 200L)
        );
        assertTrue(ex.getMessage().contains("Batch ID cannot be null"));
    }

    @Test
    @DisplayName("Internal screenCandidateResumeForBatch rejects non-existent batchId")
    void testScreenCandidateResumeForBatch_ThrowsWhenBatchNotFound() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(jobDescriptionRepository.findById(10L)).thenReturn(Optional.of(job));
        when(resumeBatchRepository.findById(999L)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                screeningService.screenCandidateResumeForBatch(999L, 10L, 200L)
        );
        assertTrue(ex.getMessage().contains("Batch not found with ID: 999"));
    }
}


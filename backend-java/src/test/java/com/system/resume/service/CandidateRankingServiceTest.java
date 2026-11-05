package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.client.AiServiceClient.BatchRankRequest;
import com.system.resume.client.AiServiceClient.BatchRankResponse;
import com.system.resume.client.AiServiceClient.RankedCandidate;
import com.system.resume.dto.CandidateRankingResponseDto;
import com.system.resume.model.Candidate;
import com.system.resume.model.CandidateRanking;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeSkill;
import com.system.resume.model.ScreeningResult;
import com.system.resume.repository.CandidateRankingRepository;
import com.system.resume.repository.JobDescriptionRepository;
import com.system.resume.repository.ResumeRepository;
import com.system.resume.repository.ResumeSkillRepository;
import com.system.resume.repository.ScreeningResultRepository;
import com.system.resume.security.SecurityAuthorizationService;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
class CandidateRankingServiceTest {

    @Mock
    private CandidateRankingRepository candidateRankingRepository;

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
    private ScreeningService screeningService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private SecurityAuthorizationService securityAuthorizationService;

    @Mock
    private TransactionStatus transactionStatus;

    private CandidateRankingService candidateRankingService;

    private JobDescription job;
    private Candidate candidate;
    private Resume resume;
    private ScreeningResult screeningResult;
    private CandidateRanking candidateRanking;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        candidateRankingService = new CandidateRankingService(
                candidateRankingRepository,
                screeningResultRepository,
                jobDescriptionRepository,
                resumeRepository,
                resumeSkillRepository,
                aiServiceClient,
                screeningService,
                transactionManager,
                securityAuthorizationService
        );

        job = new JobDescription();
        job.setId(1L);
        job.setTitle("Senior Full Stack Engineer");
        job.setDescriptionText("Seeking an experienced engineer in Java, Spring Boot, and Python.");
        job.setRequiredSkills(List.of("Java", "Spring Boot", "Python"));
        job.setMinExperienceYears(5);

        candidate = new Candidate("Jane", "Doe", "jane.doe@example.com", "555-987-6543");
        candidate.setId(42L);

        resume = new Resume(
                candidate,
                "jane_doe_resume.pdf",
                "/uploads/resumes/jane_doe_resume.pdf",
                "sha256hash",
                "application/pdf",
                2048L,
                "Jane Doe. Senior Engineer with 6 years experience in Java, Spring Boot, and Python.",
                "PARSED"
        );
        resume.setId(101L);

        screeningResult = new ScreeningResult(
                job,
                resume,
                BigDecimal.valueOf(88.50),
                BigDecimal.valueOf(90.00),
                BigDecimal.valueOf(85.00),
                List.of("Java", "Spring Boot", "Python"),
                Collections.emptyList(),
                "Candidate has 6 years experience in Java and Spring Boot.",
                "COMPLETED"
        );
        screeningResult.setId(201L);

        candidateRanking = new CandidateRanking(
                job,
                screeningResult,
                1,
                BigDecimal.valueOf(88.50),
                "SELECTED",
                "Ranked #1 with score 88.50% meeting quota and thresholds.",
                null,
                false
        );
        candidateRanking.setId(301L);
    }

    @Test
    @DisplayName("rankCandidatesForJobDto successfully retrieves JobDescription and candidates and maps DTOs within active session")
    void testRankCandidatesForJobDto_Success_WithEagerAssociations() {
        Long jobId = 1L;

        when(jobDescriptionRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(screeningResultRepository.findByJobDescriptionIdAndScreeningStatus(jobId, "COMPLETED"))
                .thenReturn(List.of(screeningResult));
        when(resumeSkillRepository.findByResumeId(resume.getId()))
                .thenReturn(List.of(new ResumeSkill(resume, "Java", "Backend", BigDecimal.valueOf(0.95))));

        RankedCandidate rankedCandidate = new RankedCandidate(
                1,
                candidate.getId(),
                resume.getId(),
                "Jane Doe",
                88.50,
                90.00,
                85.00,
                List.of("Java", "Spring Boot"),
                Collections.emptyList(),
                "Strong fit for role"
        );
        BatchRankResponse aiResponse = new BatchRankResponse(jobId, 1, List.of(rankedCandidate));
        when(aiServiceClient.rankCandidates(any(BatchRankRequest.class))).thenReturn(aiResponse);

        when(candidateRankingRepository.findByJobDescriptionIdOrderByRankPositionAsc(jobId))
                .thenReturn(List.of(candidateRanking));
        when(screeningResultRepository.findByJobDescriptionIdAndResumeId(jobId, resume.getId()))
                .thenReturn(Optional.of(screeningResult));
        when(candidateRankingRepository.saveAll(anyList())).thenReturn(List.of(candidateRanking));

        List<CandidateRankingResponseDto> dtos = candidateRankingService.rankCandidatesForJobDto(jobId, 10, 20.0);

        assertNotNull(dtos, "Ranking DTO list must not be null");
        assertEquals(1, dtos.size(), "Should return exactly one ranked candidate");

        CandidateRankingResponseDto dto = dtos.get(0);
        assertEquals(1L, dto.jobId(), "Job ID must match");
        assertEquals("Senior Full Stack Engineer", dto.jobTitle(), "Job title must be mapped without LazyInitializationException");
        assertEquals("Jane Doe", dto.candidateName(), "Candidate name must be mapped without LazyInitializationException");
        assertEquals("jane.doe@example.com", dto.candidateEmail(), "Candidate email must be mapped");
        assertEquals("jane_doe_resume.pdf", dto.resumeFileName(), "Resume file name must be mapped");
        assertEquals(1, dto.rankPosition(), "Rank position must be 1");
        assertEquals(BigDecimal.valueOf(88.50), dto.finalScore(), "Final score must match");
        assertEquals("SELECTED", dto.reviewStatus(), "Review status must be SELECTED");
    }

    @Test
    @DisplayName("rankCandidatesForJobDto handles empty candidate list gracefully when no completed screenings exist")
    void testRankCandidatesForJobDto_EmptyScreenings_ReturnsEmptyList() {
        Long jobId = 1L;

        when(jobDescriptionRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(screeningResultRepository.findByJobDescriptionIdAndScreeningStatus(jobId, "COMPLETED"))
                .thenReturn(Collections.emptyList());
        when(candidateRankingRepository.findByJobDescriptionIdOrderByRankPositionAsc(jobId))
                .thenReturn(Collections.emptyList());

        List<CandidateRankingResponseDto> dtos = candidateRankingService.rankCandidatesForJobDto(jobId, 10, 20.0);

        assertNotNull(dtos, "Result must not be null");
        assertTrue(dtos.isEmpty(), "Result should be empty when no candidates are eligible for ranking");
        verify(aiServiceClient, never()).rankCandidates(any());
    }

    @Test
    @DisplayName("rankCandidatesForJob throws IllegalArgumentException when job ID is not found")
    void testRankCandidatesForJob_JobNotFound_ThrowsException() {
        Long nonExistentJobId = 999L;
        when(jobDescriptionRepository.findById(nonExistentJobId)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> candidateRankingService.rankCandidatesForJobDto(nonExistentJobId, 10, 20.0)
        );
        assertTrue(ex.getMessage().contains("Job description not found with ID: 999"));
    }

    @Test
    @DisplayName("Regression test: CandidateRankingResponseDto.fromEntity maps lazy properties cleanly when entities are initialized")
    void testFromEntity_WithInitializedAssociations_DoesNotThrow() {
        CandidateRankingResponseDto dto = CandidateRankingResponseDto.fromEntity(candidateRanking);

        assertNotNull(dto);
        assertEquals("Senior Full Stack Engineer", dto.jobTitle());
        assertEquals("Jane Doe", dto.candidateName());
        assertEquals("jane_doe_resume.pdf", dto.resumeFileName());
        assertEquals(1, dto.rankPosition());
    }

    @Test
    @DisplayName("Regression test: demonstrates LazyInitializationException if JobDescription proxy is uninitialized outside session")
    void testFromEntity_ReproducesLazyInitializationException_WhenProxyNotInitialized() {
        JobDescription mockJobProxy = mock(JobDescription.class);
        when(mockJobProxy.getTitle()).thenThrow(new LazyInitializationException("could not initialize proxy [com.system.resume.model.JobDescription#1] - no Session"));

        CandidateRanking rankingWithUninitializedProxy = new CandidateRanking(
                mockJobProxy,
                screeningResult,
                1,
                BigDecimal.valueOf(85.0),
                "SELECTED",
                "Reason",
                null,
                false
        );

        LazyInitializationException thrown = assertThrows(
                LazyInitializationException.class,
                () -> CandidateRankingResponseDto.fromEntity(rankingWithUninitializedProxy),
                "Should throw LazyInitializationException when JobDescription proxy has no active session"
        );

        assertTrue(thrown.getMessage().contains("JobDescription#1"), "Exception message must identify JobDescription proxy");
    }
}

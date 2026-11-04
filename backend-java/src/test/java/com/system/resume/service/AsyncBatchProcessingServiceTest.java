package com.system.resume.service;

import com.system.resume.client.AiServiceClient;
import com.system.resume.client.AiServiceClient.ExtractedSkill;
import com.system.resume.client.AiServiceClient.ResumeParseResponse;
import com.system.resume.model.Candidate;
import com.system.resume.model.JobDescription;
import com.system.resume.model.Resume;
import com.system.resume.model.ResumeBatch;
import com.system.resume.model.ResumeBatchFile;
import com.system.resume.model.ResumeSkill;
import com.system.resume.repository.ResumeBatchFileRepository;
import com.system.resume.repository.ResumeBatchRepository;
import com.system.resume.repository.ResumeRepository;
import com.system.resume.repository.ResumeSkillRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncBatchProcessingServiceTest {

    @Mock
    private ResumeBatchRepository resumeBatchRepository;

    @Mock
    private ResumeBatchFileRepository resumeBatchFileRepository;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeSkillRepository resumeSkillRepository;

    @Mock
    private CandidateService candidateService;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private ScreeningService screeningService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private AsyncBatchProcessingService asyncBatchProcessingService;

    private File tempFile1;
    private File tempFile2;
    private File tempFile3;

    @BeforeEach
    void setUp() throws IOException {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        asyncBatchProcessingService = new AsyncBatchProcessingService(
                resumeBatchRepository,
                resumeBatchFileRepository,
                resumeRepository,
                resumeSkillRepository,
                candidateService,
                fileStorageService,
                aiServiceClient,
                screeningService,
                transactionManager
        );

        tempFile1 = File.createTempFile("resume_test_1_", ".pdf");
        Files.write(tempFile1.toPath(), new byte[]{1, 2, 3, 4});
        tempFile1.deleteOnExit();

        tempFile2 = File.createTempFile("resume_test_2_", ".pdf");
        Files.write(tempFile2.toPath(), new byte[]{5, 6, 7, 8});
        tempFile2.deleteOnExit();

        tempFile3 = File.createTempFile("resume_test_3_", ".pdf");
        Files.write(tempFile3.toPath(), new byte[]{9, 10, 11, 12});
        tempFile3.deleteOnExit();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (tempFile1 != null && tempFile1.exists()) tempFile1.delete();
        if (tempFile2 != null && tempFile2.exists()) tempFile2.delete();
        if (tempFile3 != null && tempFile3.exists()) tempFile3.delete();
    }

    @Test
    @DisplayName("Worker without SecurityContext successfully persists candidate, resume, and triggers auto-screening")
    void testProcessBatch_WorkerWithoutSecurityContext_SuccessfullyPersistsAndScreens() {
        SecurityContextHolder.clearContext();
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "Worker thread must run with empty SecurityContext");

        Long batchId = 100L;
        Long jobId = 10L;

        ResumeBatch batch = new ResumeBatch("Test Batch", 1);
        batch.setId(batchId);
        batch.setStatus("PENDING");

        ResumeBatchFile batchFile = new ResumeBatchFile(
                batch, "resume_john.pdf", "hash_john_123", tempFile1.getAbsolutePath(), "PENDING"
        );
        batchFile.setId(201L);

        when(resumeBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(resumeBatchFileRepository.findByBatchIdOrderByCreatedAtAsc(batchId)).thenReturn(List.of(batchFile));
        when(resumeBatchFileRepository.findById(201L)).thenReturn(Optional.of(batchFile));

        when(resumeRepository.existsByFileHash("hash_john_123")).thenReturn(false);
        when(fileStorageService.loadFileAsBytes(tempFile1.getAbsolutePath())).thenReturn(new byte[]{1, 2, 3, 4});

        ResumeParseResponse parseResponse = new ResumeParseResponse(
                "resume_john.pdf",
                "John Doe, experienced Java engineer with Spring Boot skills.",
                List.of(new ExtractedSkill("Java", "Programming Language", 0.95)),
                10,
                true,
                null,
                "John Doe",
                "john.doe@example.com",
                "555-1234"
        );
        when(aiServiceClient.parseDocument(any(), eq("resume_john.pdf"))).thenReturn(parseResponse);

        Candidate candidate = new Candidate("John", "Doe", "john.doe@example.com", "555-1234");
        candidate.setId(301L);
        when(candidateService.getOrCreateCandidate(any(), any(), any(), any())).thenReturn(candidate);

        Resume savedResume = new Resume(
                candidate, "resume_john.pdf", tempFile1.getAbsolutePath(),
                "hash_john_123", "application/pdf", 4L, parseResponse.parsed_text(), "PARSED"
        );
        savedResume.setId(401L);
        when(resumeRepository.save(any(Resume.class))).thenReturn(savedResume);

        // When batch finalizes
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "FAILED")).thenReturn(0L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PARSED")).thenReturn(1L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "DUPLICATE")).thenReturn(0L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PENDING")).thenReturn(0L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PROCESSING")).thenReturn(0L);

        // Execute background batch processing
        asyncBatchProcessingService.processBatchAsync(batchId, jobId);

        // Verify resume was persisted
        verify(resumeRepository, times(1)).save(any(Resume.class));
        verify(resumeSkillRepository, times(1)).saveAll(any());

        // Verify batch file marked as PARSED
        assertEquals("PARSED", batchFile.getStatus());

        // Verify auto-screening was called via internal batch screening method
        verify(screeningService, times(1)).screenCandidateResumeForBatch(batchId, jobId, 401L);

        // Verify batch reached COMPLETED
        assertEquals("COMPLETED", batch.getStatus());
    }

    @Test
    @DisplayName("Screening failure after persistence commit does not roll back or fail the parsed resume")
    void testProcessBatch_ScreeningFailureAfterPersistence_ResumeRemainsParsedAndCommitted() {
        SecurityContextHolder.clearContext();

        Long batchId = 101L;
        Long jobId = 10L;

        ResumeBatch batch = new ResumeBatch("Test Batch 2", 1);
        batch.setId(batchId);
        batch.setStatus("PENDING");

        ResumeBatchFile batchFile = new ResumeBatchFile(
                batch, "resume_jane.pdf", "hash_jane_456", tempFile2.getAbsolutePath(), "PENDING"
        );
        batchFile.setId(202L);

        when(resumeBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(resumeBatchFileRepository.findByBatchIdOrderByCreatedAtAsc(batchId)).thenReturn(List.of(batchFile));
        when(resumeBatchFileRepository.findById(202L)).thenReturn(Optional.of(batchFile));

        when(resumeRepository.existsByFileHash("hash_jane_456")).thenReturn(false);
        when(fileStorageService.loadFileAsBytes(tempFile2.getAbsolutePath())).thenReturn(new byte[]{5, 6, 7, 8});

        ResumeParseResponse parseResponse = new ResumeParseResponse(
                "resume_jane.pdf",
                "Jane Roe, Senior Cloud Engineer with Kubernetes experience.",
                List.of(new ExtractedSkill("Kubernetes", "DevOps", 0.9)),
                10,
                true,
                null,
                "Jane Roe",
                "jane.roe@example.com",
                "555-5678"
        );
        when(aiServiceClient.parseDocument(any(), eq("resume_jane.pdf"))).thenReturn(parseResponse);

        Candidate candidate = new Candidate("Jane", "Roe", "jane.roe@example.com", "555-5678");
        candidate.setId(302L);
        when(candidateService.getOrCreateCandidate(any(), any(), any(), any())).thenReturn(candidate);

        Resume savedResume = new Resume(
                candidate, "resume_jane.pdf", tempFile2.getAbsolutePath(),
                "hash_jane_456", "application/pdf", 4L, parseResponse.parsed_text(), "PARSED"
        );
        savedResume.setId(402L);
        when(resumeRepository.save(any(Resume.class))).thenReturn(savedResume);

        // Simulate AI service failure during auto-screening (outside DB transaction)
        doThrow(new RuntimeException("AI match microservice connection timeout"))
                .when(screeningService).screenCandidateResumeForBatch(batchId, jobId, 402L);

        // When batch finalizes
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "FAILED")).thenReturn(0L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PARSED")).thenReturn(1L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "DUPLICATE")).thenReturn(0L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PENDING")).thenReturn(0L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PROCESSING")).thenReturn(0L);

        // Execute background batch processing
        asyncBatchProcessingService.processBatchAsync(batchId, jobId);

        // Verify resume persistence transaction committed
        verify(resumeRepository, times(1)).save(any(Resume.class));

        // CRITICAL ASSERTION: Batch file status remains PARSED despite screening exception
        assertEquals("PARSED", batchFile.getStatus(),
                "Resume batch file MUST remain PARSED even if downstream screening fails");
        assertNull(batchFile.getErrorMessage(), "ErrorMessage must be null since persistence succeeded");

        // Staged file was NOT deleted
        verify(fileStorageService, never()).deleteFile(tempFile2.getAbsolutePath());

        // Batch final status is COMPLETED because the resume was successfully parsed and persisted
        assertEquals("COMPLETED", batch.getStatus());
    }

    @Test
    @DisplayName("Mixed batch outcomes: 1 PARSED, 1 DUPLICATE, 1 FAILED yields PARTIAL_SUCCESS with failure isolation")
    void testProcessBatch_MixedOutcomes_OneParsedOneDuplicateOneFailed_YieldsPartialSuccess() {
        SecurityContextHolder.clearContext();

        Long batchId = 102L;
        Long jobId = 10L;

        ResumeBatch batch = new ResumeBatch("Mixed Batch", 3);
        batch.setId(batchId);
        batch.setStatus("PENDING");

        // File 1: Will succeed (PARSED)
        ResumeBatchFile file1 = new ResumeBatchFile(
                batch, "file1_success.pdf", "hash_success_001", tempFile1.getAbsolutePath(), "PENDING"
        );
        file1.setId(201L);

        // File 2: Will be duplicate (DUPLICATE)
        ResumeBatchFile file2 = new ResumeBatchFile(
                batch, "file2_dup.pdf", "hash_duplicate_002", tempFile2.getAbsolutePath(), "PENDING"
        );
        file2.setId(202L);

        // File 3: AI parsing error (FAILED)
        ResumeBatchFile file3 = new ResumeBatchFile(
                batch, "file3_corrupt.pdf", "hash_corrupt_003", tempFile3.getAbsolutePath(), "PENDING"
        );
        file3.setId(203L);

        when(resumeBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(resumeBatchFileRepository.findByBatchIdOrderByCreatedAtAsc(batchId))
                .thenReturn(List.of(file1, file2, file3));

        when(resumeBatchFileRepository.findById(201L)).thenReturn(Optional.of(file1));
        when(resumeBatchFileRepository.findById(202L)).thenReturn(Optional.of(file2));
        when(resumeBatchFileRepository.findById(203L)).thenReturn(Optional.of(file3));

        // File 1 mocks
        when(resumeRepository.existsByFileHash("hash_success_001")).thenReturn(false);
        when(fileStorageService.loadFileAsBytes(tempFile1.getAbsolutePath())).thenReturn(new byte[]{1, 2});
        ResumeParseResponse parse1 = new ResumeParseResponse("file1_success.pdf", "Text 1", Collections.emptyList(), 5, true, null);
        when(aiServiceClient.parseDocument(any(), eq("file1_success.pdf"))).thenReturn(parse1);
        Candidate cand1 = new Candidate("Cand", "One", "cand1@example.com", null);
        when(candidateService.getOrCreateCandidate(any(), any(), any(), any())).thenReturn(cand1);
        Resume res1 = new Resume(cand1, "file1_success.pdf", tempFile1.getAbsolutePath(), "hash_success_001", "application/pdf", 2L, "Text 1", "PARSED");
        res1.setId(401L);
        when(resumeRepository.save(any(Resume.class))).thenReturn(res1);

        // File 2 mocks (duplicate)
        when(resumeRepository.existsByFileHash("hash_duplicate_002")).thenReturn(true);

        // File 3 mocks (corrupt file -> AI throws exception)
        when(resumeRepository.existsByFileHash("hash_corrupt_003")).thenReturn(false);
        when(fileStorageService.loadFileAsBytes(tempFile3.getAbsolutePath())).thenReturn(new byte[]{9, 10});
        when(aiServiceClient.parseDocument(any(), eq("file3_corrupt.pdf")))
                .thenThrow(new RuntimeException("Corrupted PDF stream cannot be read"));

        // Batch finalization counts
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "FAILED")).thenReturn(1L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PARSED")).thenReturn(1L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "DUPLICATE")).thenReturn(1L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PENDING")).thenReturn(0L);
        when(resumeBatchFileRepository.countByBatchIdAndStatus(batchId, "PROCESSING")).thenReturn(0L);

        // Execute background batch processing
        asyncBatchProcessingService.processBatchAsync(batchId, jobId);

        // File 1 reached PARSED
        assertEquals("PARSED", file1.getStatus());
        // File 2 reached DUPLICATE
        assertEquals("DUPLICATE", file2.getStatus());
        // File 3 reached FAILED
        assertEquals("FAILED", file3.getStatus());
        assertTrue(file3.getErrorMessage().contains("Corrupted PDF stream cannot be read"));

        // Duplicate file and corrupt file staged copies were cleaned up
        verify(fileStorageService, times(1)).deleteFile(tempFile2.getAbsolutePath());
        verify(fileStorageService, times(1)).deleteFile(tempFile3.getAbsolutePath());

        // Successfully parsed file was NOT deleted
        verify(fileStorageService, never()).deleteFile(tempFile1.getAbsolutePath());

        // Batch final status MUST be PARTIAL_SUCCESS
        assertEquals("PARTIAL_SUCCESS", batch.getStatus());
        assertEquals(3, batch.getProcessedFiles());
    }
}


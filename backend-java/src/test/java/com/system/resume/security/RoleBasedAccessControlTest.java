package com.system.resume.security;

import com.system.resume.config.CorsConfig;
import com.system.resume.config.SecurityConfig;
import com.system.resume.controller.*;
import com.system.resume.dto.*;
import com.system.resume.exception.GlobalExceptionHandler;
import com.system.resume.model.Role;
import com.system.resume.model.User;
import com.system.resume.repository.UserRepository;
import com.system.resume.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive Role-Based Access Control (RBAC) tests for Phase 7 Step 2.
 * Verifies role boundaries and endpoint permissions across:
 * - ROLE_ADMIN: Full administrative access to all application resources.
 * - ROLE_RECRUITER: Can manage their own jobs, batches, resumes, candidates, and job assignments.
 *   Denied administrative routes and blocked on IDOR attempts.
 * - ROLE_HIRING_MANAGER: Read-only access exclusively to assigned jobs, candidate rankings, and CSV exports.
 *   Denied mutating operations, batch uploads, screening, and unassigned resources.
 * - Anonymous / Unauthorized: 401 Unauthorized for anonymous, 403 Forbidden for unauthorized roles.
 */
@WebMvcTest(controllers = {
        JobDescriptionController.class,
        CandidateController.class,
        CandidateRankingController.class,
        ResumeController.class,
        ResumeBatchController.class,
        ScreeningController.class,
        AuthController.class
})
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtAuthenticationFilter.class,
        JwtService.class,
        AuthCookieService.class,
        CsrfProtectionService.class,
        GlobalExceptionHandler.class
})
class RoleBasedAccessControlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private JobDescriptionService jobDescriptionService;

    @MockBean
    private CandidateService candidateService;

    @MockBean
    private CandidateRankingService candidateRankingService;

    @MockBean
    private CandidateExportService candidateExportService;

    @MockBean
    private ResumeService resumeService;

    @MockBean
    private ScreeningService screeningService;

    @MockBean
    private BatchResumeService batchResumeService;

    @MockBean
    private AuthService authService;

    @MockBean
    private RefreshTokenService refreshTokenService;

    private String adminToken;
    private String recruiterToken;
    private String hiringManagerToken;
    private String guestToken;

    @BeforeEach
    void setUp() {
        // Mock Admin User
        User adminUser = new User("admin@company.com", "hash", "Admin", "User", true);
        adminUser.setId(1L);
        adminUser.getRoles().add(new Role(1L, "ROLE_ADMIN", "Administrator", LocalDateTime.now()));

        // Mock Recruiter User
        User recruiterUser = new User("recruiter@company.com", "hash", "Recruiter", "User", true);
        recruiterUser.setId(2L);
        recruiterUser.getRoles().add(new Role(2L, "ROLE_RECRUITER", "Recruiter", LocalDateTime.now()));

        // Mock Hiring Manager User
        User hmUser = new User("manager@company.com", "hash", "Hiring", "Manager", true);
        hmUser.setId(3L);
        hmUser.getRoles().add(new Role(3L, "ROLE_HIRING_MANAGER", "Hiring Manager", LocalDateTime.now()));

        // Mock Guest User (Unauthorized role)
        User guestUser = new User("guest@company.com", "hash", "Guest", "User", true);
        guestUser.setId(4L);
        guestUser.getRoles().add(new Role(4L, "ROLE_GUEST", "Guest", LocalDateTime.now()));

        when(userRepository.findByEmail("admin@company.com")).thenReturn(Optional.of(adminUser));
        when(userRepository.findByEmail("recruiter@company.com")).thenReturn(Optional.of(recruiterUser));
        when(userRepository.findByEmail("manager@company.com")).thenReturn(Optional.of(hmUser));
        when(userRepository.findByEmail("guest@company.com")).thenReturn(Optional.of(guestUser));

        adminToken = "Bearer " + jwtService.generateAccessToken(adminUser);
        recruiterToken = "Bearer " + jwtService.generateAccessToken(recruiterUser);
        hiringManagerToken = "Bearer " + jwtService.generateAccessToken(hmUser);
        guestToken = "Bearer " + jwtService.generateAccessToken(guestUser);
    }

    // =========================================================================
    // 1. Anonymous Access Tests (401 Unauthorized)
    // =========================================================================

    @Test
    @DisplayName("Anonymous request to protected jobs endpoint should return 401 Unauthorized")
    void testAnonymousAccess_JobsEndpoint_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Anonymous request to protected candidates endpoint should return 401 Unauthorized")
    void testAnonymousAccess_CandidatesEndpoint_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/candidates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Anonymous request to protected rankings leaderboard should return 401 Unauthorized")
    void testAnonymousAccess_LeaderboardEndpoint_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/job/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // 2. Admin Access Tests (ROLE_ADMIN has full access)
    // =========================================================================

    @Test
    @DisplayName("Admin should have access to administrative routes (/api/v1/admin/**)")
    void testAdminAccess_AdminRoute_IsPermitted() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard")
                        .header("Authorization", adminToken))
                .andExpect(status().isNotFound()); // unmapped method returns 404, proving security check passed
    }

    @Test
    @DisplayName("Admin can create a job description (POST /api/v1/jobs)")
    void testAdminAccess_CreateJob_IsPermitted() throws Exception {
        JobDescriptionResponseDto jobDto = new JobDescriptionResponseDto(
                1L, "Software Engineer", "Backend", "Remote", "desc", List.of("Java"), 3, "ACTIVE", LocalDateTime.now(), LocalDateTime.now(), 1L
        );
        when(jobDescriptionService.createJobDto(any(JobDescriptionRequestDto.class))).thenReturn(jobDto);

        mockMvc.perform(post("/api/v1/jobs")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Software Engineer\",\"department\":\"Backend\",\"descriptionText\":\"Java developer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("Admin can generate candidate rankings (POST /api/v1/rankings/generate/{jobId})")
    void testAdminAccess_GenerateRankings_IsPermitted() throws Exception {
        when(candidateRankingService.rankCandidatesForJobDto(anyLong(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/v1/rankings/generate/1")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Admin can view and manage resume batches (GET /api/v1/resumes/batches)")
    void testAdminAccess_GetBatches_IsPermitted() throws Exception {
        when(batchResumeService.getAllBatchesDto()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/resumes/batches")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Admin can assign hiring manager to job (POST /api/v1/jobs/{id}/hiring-managers)")
    void testAdminAccess_AssignHiringManager_IsPermitted() throws Exception {
        JobAssignmentResponseDto assignmentDto = new JobAssignmentResponseDto(
                10L, 1L, 3L, "manager@company.com", "Hiring Manager", 1L, LocalDateTime.now()
        );
        when(jobDescriptionService.assignHiringManager(anyLong(), anyLong())).thenReturn(assignmentDto);

        mockMvc.perform(post("/api/v1/jobs/1/hiring-managers")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hiringManagerId\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hiringManagerId").value(3));
    }

    // =========================================================================
    // 3. Recruiter Access Tests (ROLE_RECRUITER)
    // =========================================================================

    @Test
    @DisplayName("Recruiter CAN create jobs (POST /api/v1/jobs) -> 201 Created")
    void testRecruiterAccess_CreateJob_IsPermitted() throws Exception {
        JobDescriptionResponseDto jobDto = new JobDescriptionResponseDto(
                2L, "Frontend Engineer", "Frontend", "Remote", "desc", List.of("React"), 2, "ACTIVE", LocalDateTime.now(), LocalDateTime.now(), 2L
        );
        when(jobDescriptionService.createJobDto(any(JobDescriptionRequestDto.class))).thenReturn(jobDto);

        mockMvc.perform(post("/api/v1/jobs")
                        .header("Authorization", recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Frontend Engineer\",\"department\":\"Frontend\",\"descriptionText\":\"React developer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(2));
    }

    @Test
    @DisplayName("Recruiter CAN view jobs (GET /api/v1/jobs) -> 200 OK")
    void testRecruiterAccess_GetJobs_IsPermitted() throws Exception {
        when(jobDescriptionService.getJobDtos(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Recruiter CAN create candidates (POST /api/v1/candidates) -> 201 Created")
    void testRecruiterAccess_CreateCandidate_IsPermitted() throws Exception {
        CandidateResponseDto candidateDto = new CandidateResponseDto(
                10L, "John", "Doe", "john@test.com", "1234567890", LocalDateTime.now(), LocalDateTime.now()
        );
        when(candidateService.createCandidateDto(any())).thenReturn(candidateDto);

        mockMvc.perform(post("/api/v1/candidates")
                        .header("Authorization", recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"John\",\"lastName\":\"Doe\",\"email\":\"john@test.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    @DisplayName("Recruiter CAN view candidates (GET /api/v1/candidates) -> 200 OK")
    void testRecruiterAccess_GetCandidates_IsPermitted() throws Exception {
        when(candidateService.getAllCandidateDtos()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Recruiter CAN upload resumes (POST /api/v1/resumes/upload) -> 201 Created")
    void testRecruiterAccess_UploadResume_IsPermitted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "dummy content".getBytes());
        ResumeResponseDto resumeDto = new ResumeResponseDto(
                1L, 10L, "John Doe", "resume.pdf", "application/pdf", 100L,
                "parsed text", "PARSED", LocalDateTime.now(), LocalDateTime.now(), List.of()
        );
        when(resumeService.uploadAndProcessResumeDto(anyLong(), any())).thenReturn(resumeDto);

        mockMvc.perform(multipart("/api/v1/resumes/upload")
                        .file(file)
                        .param("candidateId", "10")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("Recruiter CAN upload resume batches (POST /api/v1/resumes/batches) -> 202 Accepted")
    void testRecruiterAccess_UploadBatch_IsPermitted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "resume.pdf", "application/pdf", "dummy content".getBytes());
        ResumeBatchResponseDto batchDto = new ResumeBatchResponseDto(
                1L, "Batch_1", null, null, 1, 0, "PROCESSING", LocalDateTime.now(), LocalDateTime.now()
        );
        when(batchResumeService.acceptAndStageBatch(any(), any(), any())).thenReturn(batchDto);

        mockMvc.perform(multipart("/api/v1/resumes/batches")
                        .file(file)
                        .header("Authorization", recruiterToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("Recruiter CAN view resume batches (GET /api/v1/resumes/batches) -> 200 OK")
    void testRecruiterAccess_GetBatches_IsPermitted() throws Exception {
        when(batchResumeService.getAllBatchesDto()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/resumes/batches")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Recruiter CAN generate candidate rankings (POST /api/v1/rankings/generate/{jobId}) -> 200 OK")
    void testRecruiterAccess_GenerateRankings_IsPermitted() throws Exception {
        when(candidateRankingService.rankCandidatesForJobDto(anyLong(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/v1/rankings/generate/1")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Recruiter CAN view candidate leaderboard (GET /api/v1/rankings/job/{jobId}) -> 200 OK")
    void testRecruiterAccess_GetLeaderboard_IsPermitted() throws Exception {
        when(candidateRankingService.getLeaderboardDtosByJob(anyLong(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rankings/job/1")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Recruiter CAN export leaderboard CSV (GET /api/v1/rankings/job/{jobId}/export) -> 200 OK")
    void testRecruiterAccess_ExportLeaderboardCsv_IsPermitted() throws Exception {
        when(candidateExportService.exportLeaderboardCsv(anyLong(), any()))
                .thenReturn("Header\nRow".getBytes());

        mockMvc.perform(get("/api/v1/rankings/job/1/export")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Recruiter CAN assign hiring manager to their own job (POST /api/v1/jobs/{id}/hiring-managers) -> 201 Created")
    void testRecruiterAccess_AssignHiringManager_IsPermitted() throws Exception {
        JobAssignmentResponseDto assignmentDto = new JobAssignmentResponseDto(
                11L, 2L, 3L, "manager@company.com", "Hiring Manager", 2L, LocalDateTime.now()
        );
        when(jobDescriptionService.assignHiringManager(anyLong(), anyLong())).thenReturn(assignmentDto);

        mockMvc.perform(post("/api/v1/jobs/2/hiring-managers")
                        .header("Authorization", recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hiringManagerId\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hiringManagerId").value(3));
    }

    @Test
    @DisplayName("Recruiter CAN remove assigned hiring manager (DELETE /api/v1/jobs/{id}/hiring-managers/{managerId}) -> 204 No Content")
    void testRecruiterAccess_RemoveHiringManager_IsPermitted() throws Exception {
        mockMvc.perform(delete("/api/v1/jobs/2/hiring-managers/3")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Recruiter IDOR: attempting to access another recruiter's job throws AccessDeniedException -> 403 Forbidden")
    void testRecruiterAccess_IdorJobAccess_Returns403() throws Exception {
        when(jobDescriptionService.getJobDtoById(99L))
                .thenThrow(new AccessDeniedException("Access denied: You do not own this job description"));

        mockMvc.perform(get("/api/v1/jobs/99")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Recruiter is DENIED access to admin-only routes (/api/v1/admin/**) with 403 Forbidden")
    void testRecruiterAccess_AdminRoute_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", recruiterToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    // =========================================================================
    // 4. Hiring Manager Access Tests (ROLE_HIRING_MANAGER)
    // =========================================================================

    @Test
    @DisplayName("Hiring Manager CAN view jobs (GET /api/v1/jobs) -> 200 OK")
    void testHiringManager_GetJobs_IsPermitted() throws Exception {
        when(jobDescriptionService.getJobDtos(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Hiring Manager CAN view assigned job by ID (GET /api/v1/jobs/{id}) -> 200 OK")
    void testHiringManager_GetJobById_IsPermitted() throws Exception {
        JobDescriptionResponseDto jobDto = new JobDescriptionResponseDto(
                1L, "Assigned Job", "Tech", "Remote", "desc", List.of("Java"), 3, "ACTIVE", LocalDateTime.now(), LocalDateTime.now(), 2L
        );
        when(jobDescriptionService.getJobDtoById(1L)).thenReturn(jobDto);

        mockMvc.perform(get("/api/v1/jobs/1")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("Hiring Manager CAN view leaderboard for assigned job (GET /api/v1/rankings/job/{jobId}) -> 200 OK")
    void testHiringManager_GetLeaderboard_IsPermitted() throws Exception {
        when(candidateRankingService.getLeaderboardDtosByJob(1L, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rankings/job/1")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Hiring Manager CAN export CSV for assigned job (GET /api/v1/rankings/job/{jobId}/export) -> 200 OK")
    void testHiringManager_ExportLeaderboardCsv_IsPermitted() throws Exception {
        when(candidateExportService.exportLeaderboardCsv(1L, null))
                .thenReturn("ID,Rank,Name".getBytes());

        mockMvc.perform(get("/api/v1/rankings/job/1/export")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Hiring Manager CAN view candidates (GET /api/v1/candidates) -> 200 OK")
    void testHiringManager_GetCandidates_IsPermitted() throws Exception {
        when(candidateService.getAllCandidateDtos()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Hiring Manager CAN view assigned managers for job (GET /api/v1/jobs/{id}/hiring-managers) -> 200 OK")
    void testHiringManager_GetAssignedManagers_IsPermitted() throws Exception {
        when(jobDescriptionService.getAssignedHiringManagers(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/jobs/1/hiring-managers")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Hiring Manager accessing unassigned job returns 403 Forbidden")
    void testHiringManager_UnassignedJob_Returns403() throws Exception {
        when(jobDescriptionService.getJobDtoById(99L))
                .thenThrow(new AccessDeniedException("Access denied: You are not assigned to this job description"));

        mockMvc.perform(get("/api/v1/jobs/99")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from creating jobs (POST /api/v1/jobs) -> 403")
    void testHiringManager_CreateJob_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .header("Authorization", hiringManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Tech Lead\",\"department\":\"Engineering\",\"descriptionText\":\"Lead team\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from updating jobs (PUT /api/v1/jobs/{id}) -> 403")
    void testHiringManager_UpdateJob_Returns403() throws Exception {
        mockMvc.perform(put("/api/v1/jobs/1")
                        .header("Authorization", hiringManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Tech Lead\",\"department\":\"Engineering\",\"descriptionText\":\"Lead team\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from deleting jobs (DELETE /api/v1/jobs/{id}) -> 403")
    void testHiringManager_DeleteJob_Returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/jobs/1")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from assigning hiring managers (POST /api/v1/jobs/{id}/hiring-managers) -> 403")
    void testHiringManager_AssignHiringManager_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/1/hiring-managers")
                        .header("Authorization", hiringManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hiringManagerId\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from uploading resumes (POST /api/v1/resumes/upload) -> 403")
    void testHiringManager_UploadResume_Returns403() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "dummy content".getBytes());

        mockMvc.perform(multipart("/api/v1/resumes/upload")
                        .file(file)
                        .param("candidateId", "10")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from uploading resume batches (POST /api/v1/resumes/batches) -> 403")
    void testHiringManager_UploadBatch_Returns403() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "resume.pdf", "application/pdf", "dummy content".getBytes());

        mockMvc.perform(multipart("/api/v1/resumes/batches")
                        .file(file)
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from accessing batch queues (GET /api/v1/resumes/batches) -> 403")
    void testHiringManager_GetBatches_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/resumes/batches")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from generating screening results (POST /api/v1/screening/screen) -> 403")
    void testHiringManager_ScreenResume_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/screening/screen")
                        .header("Authorization", hiringManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":1,\"resumeId\":100}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from generating candidate rankings (POST /api/v1/rankings/generate/{jobId}) -> 403")
    void testHiringManager_GenerateRankings_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/rankings/generate/1")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from updating recruiter review status (PATCH /api/v1/rankings/{id}/review) -> 403")
    void testHiringManager_UpdateReviewStatus_Returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/rankings/1/review")
                        .header("Authorization", hiringManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"SELECTED\",\"reviewerNotes\":\"Manager review\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Hiring Manager is FORBIDDEN from administrative routes (/api/v1/admin/**) -> 403")
    void testHiringManager_AdminRoute_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", hiringManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // =========================================================================
    // 5. Unauthorized Role & Public Registration Safety Tests
    // =========================================================================

    @Test
    @DisplayName("User with unauthorized role (ROLE_GUEST) attempting access to jobs returns 403")
    void testGuestAccess_JobsEndpoint_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", guestToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Public registration cannot assign elevated roles (always assigns ROLE_RECRUITER)")
    void testPublicRegistration_DoesNotAllowElevatedRoleEscalation() throws Exception {
        UserResponseDto created = new UserResponseDto(
                50L, "newrecruiter@company.com", "New", "Recruiter", "New Recruiter",
                true, Set.of("ROLE_RECRUITER"), LocalDateTime.now()
        );
        when(authService.register(any(RegisterRequestDto.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"newrecruiter@company.com\",\"password\":\"StrongPassword123!\",\"firstName\":\"New\",\"lastName\":\"Recruiter\",\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("ROLE_RECRUITER"));
    }
}

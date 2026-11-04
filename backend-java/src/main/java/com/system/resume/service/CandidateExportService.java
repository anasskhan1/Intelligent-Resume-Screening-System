package com.system.resume.service;

import com.system.resume.dto.CandidateRankingResponseDto;
import com.system.resume.repository.JobDescriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Service responsible for exporting candidate rankings and screening evaluations
 * into standards-compliant, injection-safe CSV files.
 *
 * Implements:
 * - RFC 4180 quotation and delimiter escaping.
 * - Formula injection protection (CWE-1236 / Spreadsheet Injection).
 * - UTF-8 Byte Order Mark (BOM) inclusion for seamless Microsoft Excel compatibility.
 */
@Service
public class CandidateExportService {

    private static final Logger logger = LoggerFactory.getLogger(CandidateExportService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CandidateRankingService candidateRankingService;
    private final JobDescriptionRepository jobDescriptionRepository;

    public CandidateExportService(CandidateRankingService candidateRankingService,
                                  JobDescriptionRepository jobDescriptionRepository) {
        this.candidateRankingService = candidateRankingService;
        this.jobDescriptionRepository = jobDescriptionRepository;
    }

    /**
     * Generates an injection-safe, UTF-8 BOM encoded CSV of candidate rankings for a job.
     *
     * @param jobId target job description ID
     * @param reviewStatus optional review status filter (e.g. SELECTED, SHORTLISTED, REJECTED)
     * @return byte array containing UTF-8 BOM and CSV content
     */
    public byte[] exportLeaderboardCsv(Long jobId, String reviewStatus) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null.");
        }
        if (!jobDescriptionRepository.existsById(jobId)) {
            throw new IllegalArgumentException("Job description not found with ID: " + jobId);
        }

        List<CandidateRankingResponseDto> rankings = candidateRankingService.getLeaderboardDtosByJob(jobId, reviewStatus);
        logger.info("Exporting CSV for Job ID {} (reviewStatus: {}) - {} candidates found",
                jobId, reviewStatus != null ? reviewStatus : "ALL", rankings.size());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // 1. Write UTF-8 BOM (\uFEFF: 0xEF, 0xBB, 0xBF) for Excel encoding detection
        try {
            baos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        } catch (IOException e) {
            throw new RuntimeException("Failed to write UTF-8 BOM to CSV export stream", e);
        }

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            // 2. CSV Headers
            writer.println(String.join(",",
                    "Rank Position",
                    "Candidate Name",
                    "Email",
                    "Phone",
                    "Overall Match Score (%)",
                    "Review Status",
                    "Original AI Recommendation",
                    "Manual Override",
                    "Years of Experience",
                    "Skills Match Score (%)",
                    "Experience Match Score (%)",
                    "Matched Skills",
                    "Missing Skills",
                    "AI Decision Rationale",
                    "Recruiter Justification Notes",
                    "Target Job Title",
                    "Resume File Name",
                    "Ranked At"
            ));

            // 3. Data Rows
            for (CandidateRankingResponseDto r : rankings) {
                String originalRecommendation = determineOriginalRecommendation(r.decisionReason());
                String manualOverride = Boolean.TRUE.equals(r.isManualOverride()) ? "YES" : "NO";
                String overallScore = r.finalScore() != null
                        ? String.format(Locale.ROOT, "%.1f", r.finalScore())
                        : "0.0";
                String skillsScore = r.skillsScore() != null
                        ? String.format(Locale.ROOT, "%.1f", r.skillsScore())
                        : "N/A";
                String expScore = r.experienceScore() != null
                        ? String.format(Locale.ROOT, "%.1f", r.experienceScore())
                        : "N/A";
                String expYears = r.experienceYears() != null
                        ? String.valueOf(r.experienceYears())
                        : "N/A";
                String matchedSkills = (r.matchedSkills() != null && !r.matchedSkills().isEmpty())
                        ? String.join("; ", r.matchedSkills())
                        : "None";
                String missingSkills = (r.missingSkills() != null && !r.missingSkills().isEmpty())
                        ? String.join("; ", r.missingSkills())
                        : "None";
                String rankedAt = r.rankedAt() != null
                        ? r.rankedAt().format(TIMESTAMP_FORMATTER)
                        : "";

                writer.println(String.join(",",
                        escapeCsvField(String.valueOf(r.rankPosition())),
                        escapeCsvField(r.candidateName()),
                        escapeCsvField(r.candidateEmail()),
                        escapeCsvField(r.candidatePhone() != null ? r.candidatePhone() : "N/A"),
                        escapeCsvField(overallScore),
                        escapeCsvField(r.reviewStatus()),
                        escapeCsvField(originalRecommendation),
                        escapeCsvField(manualOverride),
                        escapeCsvField(expYears),
                        escapeCsvField(skillsScore),
                        escapeCsvField(expScore),
                        escapeCsvField(matchedSkills),
                        escapeCsvField(missingSkills),
                        escapeCsvField(r.decisionReason()),
                        escapeCsvField(r.reviewerNotes() != null ? r.reviewerNotes() : ""),
                        escapeCsvField(r.jobTitle()),
                        escapeCsvField(r.resumeFileName() != null ? r.resumeFileName() : "N/A"),
                        escapeCsvField(rankedAt)
                ));
            }

            writer.flush();
        }

        return baos.toByteArray();
    }

    /**
     * Determines whether the original deterministic AI recommendation was SELECTED or REJECTED
     * before any recruiter manual override was applied.
     */
    private String determineOriginalRecommendation(String decisionReason) {
        if (decisionReason == null || decisionReason.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = decisionReason.trim().toUpperCase(Locale.ROOT);
        if (trimmed.startsWith("SELECTED")) {
            return "SELECTED";
        } else if (trimmed.startsWith("REJECTED")) {
            return "REJECTED";
        }
        return "EVALUATED";
    }

    /**
     * Escapes a single CSV field value according to RFC 4180 and protects against
     * CSV Formula Injection (CWE-1236).
     *
     * Rules:
     * 1. Neutralize formula injection: if the field starts with '=', '+', '-', '@', '\t', or '\r',
     *    prepend a single quote (').
     * 2. Enclose in double quotes if the value contains a comma, newline, quote, or leading formula character.
     * 3. Escape internal quotes by doubling them ("").
     */
    public static String escapeCsvField(String value) {
        if (value == null) {
            return "\"\"";
        }

        String sanitized = value;

        // Formula injection mitigation: prepend single quote if value begins with executable formula prefix
        if (!sanitized.isEmpty()) {
            char firstChar = sanitized.charAt(0);
            if (firstChar == '=' || firstChar == '+' || firstChar == '-' || firstChar == '@'
                    || firstChar == '\t' || firstChar == '\r') {
                sanitized = "'" + sanitized;
            }
        }

        // Always wrap in quotes if contains comma, quote, newline, or if sanitized with leading quote
        boolean needsQuotes = sanitized.contains(",")
                || sanitized.contains("\"")
                || sanitized.contains("\n")
                || sanitized.contains("\r")
                || sanitized.startsWith("'");

        if (needsQuotes) {
            sanitized = sanitized.replace("\"", "\"\"");
            return "\"" + sanitized + "\"";
        }

        return sanitized;
    }
}


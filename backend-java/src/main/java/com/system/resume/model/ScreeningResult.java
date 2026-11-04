package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "screening_results",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_job_resume_screening", columnNames = {"job_description_id", "resume_id"})
    },
    indexes = {
        @Index(name = "idx_screening_job_score", columnList = "job_description_id, match_score DESC")
    }
)
public class ScreeningResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_description_id", nullable = false, foreignKey = @ForeignKey(name = "fk_screening_job"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private JobDescription jobDescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false, foreignKey = @ForeignKey(name = "fk_screening_resume"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Resume resume;

    @Column(name = "match_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal matchScore;

    @Column(name = "skills_score", precision = 5, scale = 2)
    private BigDecimal skillsScore;

    @Column(name = "experience_score", precision = 5, scale = 2)
    private BigDecimal experienceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_skills", columnDefinition = "json")
    private List<String> matchedSkills = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_skills", columnDefinition = "json")
    private List<String> missingSkills = new ArrayList<>();

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "screening_status", nullable = false, length = 50)
    private String screeningStatus = "COMPLETED";

    @CreationTimestamp
    @Column(name = "screened_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime screenedAt;

    public ScreeningResult() {
    }

    public ScreeningResult(JobDescription jobDescription, Resume resume, BigDecimal matchScore,
                           BigDecimal skillsScore, BigDecimal experienceScore, List<String> matchedSkills,
                           List<String> missingSkills, String aiSummary, String screeningStatus) {
        this.jobDescription = jobDescription;
        this.resume = resume;
        this.matchScore = matchScore;
        this.skillsScore = skillsScore;
        this.experienceScore = experienceScore;
        this.matchedSkills = matchedSkills != null ? matchedSkills : new ArrayList<>();
        this.missingSkills = missingSkills != null ? missingSkills : new ArrayList<>();
        this.aiSummary = aiSummary;
        this.screeningStatus = screeningStatus != null ? screeningStatus : "COMPLETED";
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public JobDescription getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(JobDescription jobDescription) {
        this.jobDescription = jobDescription;
    }

    public Resume getResume() {
        return resume;
    }

    public void setResume(Resume resume) {
        this.resume = resume;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(BigDecimal matchScore) {
        this.matchScore = matchScore;
    }

    public BigDecimal getSkillsScore() {
        return skillsScore;
    }

    public void setSkillsScore(BigDecimal skillsScore) {
        this.skillsScore = skillsScore;
    }

    public BigDecimal getExperienceScore() {
        return experienceScore;
    }

    public void setExperienceScore(BigDecimal experienceScore) {
        this.experienceScore = experienceScore;
    }

    public List<String> getMatchedSkills() {
        return matchedSkills;
    }

    public void setMatchedSkills(List<String> matchedSkills) {
        this.matchedSkills = matchedSkills != null ? matchedSkills : new ArrayList<>();
    }

    public List<String> getMissingSkills() {
        return missingSkills;
    }

    public void setMissingSkills(List<String> missingSkills) {
        this.missingSkills = missingSkills != null ? missingSkills : new ArrayList<>();
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public String getScreeningStatus() {
        return screeningStatus;
    }

    public void setScreeningStatus(String screeningStatus) {
        this.screeningStatus = screeningStatus != null ? screeningStatus : "COMPLETED";
    }

    public LocalDateTime getScreenedAt() {
        return screenedAt;
    }

    public void setScreenedAt(LocalDateTime screenedAt) {
        this.screenedAt = screenedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScreeningResult that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ScreeningResult{" +
                "id=" + id +
                ", jobDescriptionId=" + (jobDescription != null ? jobDescription.getId() : null) +
                ", resumeId=" + (resume != null ? resume.getId() : null) +
                ", matchScore=" + matchScore +
                ", skillsScore=" + skillsScore +
                ", experienceScore=" + experienceScore +
                ", screeningStatus='" + screeningStatus + '\'' +
                ", screenedAt=" + screenedAt +
                '}';
    }
}

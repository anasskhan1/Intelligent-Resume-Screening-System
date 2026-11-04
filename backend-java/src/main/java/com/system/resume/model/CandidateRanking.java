package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "candidate_rankings",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_job_ranking_position", columnNames = {"job_description_id", "rank_position"}),
        @UniqueConstraint(name = "uk_job_screening_result", columnNames = {"job_description_id", "screening_result_id"})
    },
    indexes = {
        @Index(name = "idx_rankings_job_position", columnList = "job_description_id, rank_position ASC")
    }
)
public class CandidateRanking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_description_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rankings_job"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private JobDescription jobDescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "screening_result_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rankings_screening"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ScreeningResult screeningResult;

    @Column(name = "rank_position", nullable = false, columnDefinition = "INT UNSIGNED")
    private Integer rankPosition;

    @Column(name = "final_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal finalScore;

    @Column(name = "review_status", nullable = false, length = 50)
    private String reviewStatus = "SHORTLISTED";

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    @Column(name = "reviewer_notes", columnDefinition = "TEXT")
    private String reviewerNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id", foreignKey = @ForeignKey(name = "fk_ranking_reviewed_by"))
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "is_manual_override", nullable = false)
    private Boolean isManualOverride = false;

    @CreationTimestamp
    @Column(name = "ranked_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime rankedAt;

    public CandidateRanking() {
    }

    public CandidateRanking(JobDescription jobDescription, ScreeningResult screeningResult,
                            Integer rankPosition, BigDecimal finalScore, String reviewStatus,
                            String reviewerNotes) {
        this.jobDescription = jobDescription;
        this.screeningResult = screeningResult;
        this.rankPosition = rankPosition;
        this.finalScore = finalScore;
        this.reviewStatus = reviewStatus != null ? reviewStatus : "SHORTLISTED";
        this.reviewerNotes = reviewerNotes;
        this.isManualOverride = false;
    }

    public CandidateRanking(JobDescription jobDescription, ScreeningResult screeningResult,
                            Integer rankPosition, BigDecimal finalScore, String reviewStatus,
                            String decisionReason, String reviewerNotes, Boolean isManualOverride) {
        this.jobDescription = jobDescription;
        this.screeningResult = screeningResult;
        this.rankPosition = rankPosition;
        this.finalScore = finalScore;
        this.reviewStatus = reviewStatus != null ? reviewStatus : "SHORTLISTED";
        this.decisionReason = decisionReason;
        this.reviewerNotes = reviewerNotes;
        this.isManualOverride = isManualOverride != null ? isManualOverride : false;
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

    public ScreeningResult getScreeningResult() {
        return screeningResult;
    }

    public void setScreeningResult(ScreeningResult screeningResult) {
        this.screeningResult = screeningResult;
    }

    public Integer getRankPosition() {
        return rankPosition;
    }

    public void setRankPosition(Integer rankPosition) {
        this.rankPosition = rankPosition;
    }

    public BigDecimal getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(BigDecimal finalScore) {
        this.finalScore = finalScore;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus != null ? reviewStatus : "SHORTLISTED";
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public String getReviewerNotes() {
        return reviewerNotes;
    }

    public void setReviewerNotes(String reviewerNotes) {
        this.reviewerNotes = reviewerNotes;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(User reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Boolean getIsManualOverride() {
        return isManualOverride;
    }

    public Boolean isManualOverride() {
        return isManualOverride;
    }

    public void setIsManualOverride(Boolean isManualOverride) {
        this.isManualOverride = isManualOverride != null ? isManualOverride : false;
    }

    public LocalDateTime getRankedAt() {
        return rankedAt;
    }

    public void setRankedAt(LocalDateTime rankedAt) {
        this.rankedAt = rankedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CandidateRanking that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "CandidateRanking{" +
                "id=" + id +
                ", jobDescriptionId=" + (jobDescription != null ? jobDescription.getId() : null) +
                ", screeningResultId=" + (screeningResult != null ? screeningResult.getId() : null) +
                ", rankPosition=" + rankPosition +
                ", finalScore=" + finalScore +
                ", reviewStatus='" + reviewStatus + '\'' +
                ", decisionReason='" + decisionReason + '\'' +
                ", isManualOverride=" + isManualOverride +
                ", rankedAt=" + rankedAt +
                '}';
    }
}

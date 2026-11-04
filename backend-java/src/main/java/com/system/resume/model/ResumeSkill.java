package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "resume_skills", indexes = {
    @Index(name = "idx_skills_resume", columnList = "resume_id"),
    @Index(name = "idx_skills_name", columnList = "skill_name")
})
public class ResumeSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false, foreignKey = @ForeignKey(name = "fk_skills_resume"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Resume resume;

    @Column(name = "skill_name", nullable = false, length = 100)
    private String skillName;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "confidence_score", precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    public ResumeSkill() {
    }

    public ResumeSkill(Resume resume, String skillName, String category, BigDecimal confidenceScore) {
        this.resume = resume;
        this.skillName = skillName;
        this.category = category;
        this.confidenceScore = confidenceScore;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Resume getResume() {
        return resume;
    }

    public void setResume(Resume resume) {
        this.resume = resume;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(BigDecimal confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResumeSkill that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ResumeSkill{" +
                "id=" + id +
                ", resumeId=" + (resume != null ? resume.getId() : null) +
                ", skillName='" + skillName + '\'' +
                ", category='" + category + '\'' +
                ", confidenceScore=" + confidenceScore +
                ", createdAt=" + createdAt +
                '}';
    }
}

package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Entity mapping the many-to-many relationship between a JobDescription
 * and an assigned User with ROLE_HIRING_MANAGER, including auditing fields
 * (assigned_by_user_id and assigned_at).
 */
@Entity
@Table(name = "job_hiring_managers",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_job_manager", columnNames = {"job_description_id", "user_id"})
    },
    indexes = {
        @Index(name = "idx_jhm_user", columnList = "user_id"),
        @Index(name = "idx_jhm_assigner", columnList = "assigned_by_user_id")
    }
)
public class JobHiringManager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_description_id", nullable = false, foreignKey = @ForeignKey(name = "fk_jhm_job"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private JobDescription jobDescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_jhm_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User hiringManager;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_user_id", foreignKey = @ForeignKey(name = "fk_jhm_assigner"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User assignedBy;

    @CreationTimestamp
    @Column(name = "assigned_at", nullable = false, updatable = false, columnDefinition = "DATETIME")
    private LocalDateTime assignedAt;

    public JobHiringManager() {
    }

    public JobHiringManager(JobDescription jobDescription, User hiringManager, User assignedBy) {
        this.jobDescription = jobDescription;
        this.hiringManager = hiringManager;
        this.assignedBy = assignedBy;
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

    public User getHiringManager() {
        return hiringManager;
    }

    public void setHiringManager(User hiringManager) {
        this.hiringManager = hiringManager;
    }

    public User getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(User assignedBy) {
        this.assignedBy = assignedBy;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JobHiringManager that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "JobHiringManager{" +
                "id=" + id +
                ", jobId=" + (jobDescription != null ? jobDescription.getId() : null) +
                ", hiringManagerId=" + (hiringManager != null ? hiringManager.getId() : null) +
                ", assignedById=" + (assignedBy != null ? assignedBy.getId() : null) +
                ", assignedAt=" + assignedAt +
                '}';
    }
}


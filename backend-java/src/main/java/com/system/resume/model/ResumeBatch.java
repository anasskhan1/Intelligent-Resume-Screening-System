package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "resume_batches", indexes = {
    @Index(name = "idx_resume_batches_job_id", columnList = "job_description_id")
})
public class ResumeBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_name", nullable = false, length = 255)
    private String batchName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_description_id", foreignKey = @ForeignKey(name = "fk_resume_batches_job_description"))
    private JobDescription jobDescription;

    @Column(name = "total_files", nullable = false)
    private Integer totalFiles = 0;

    @Column(name = "processed_files", nullable = false)
    private Integer processedFiles = 0;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "PENDING";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id", foreignKey = @ForeignKey(name = "fk_batch_uploaded_by"))
    private User uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    public ResumeBatch() {
    }

    public ResumeBatch(String batchName, Integer totalFiles) {
        this.batchName = batchName;
        this.totalFiles = totalFiles != null ? totalFiles : 0;
        this.processedFiles = 0;
        this.status = "PENDING";
    }

    public ResumeBatch(String batchName, Integer totalFiles, Integer processedFiles, String status) {
        this.batchName = batchName;
        this.totalFiles = totalFiles != null ? totalFiles : 0;
        this.processedFiles = processedFiles != null ? processedFiles : 0;
        this.status = status != null ? status : "PENDING";
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchName() {
        return batchName;
    }

    public void setBatchName(String batchName) {
        this.batchName = batchName;
    }

    public JobDescription getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(JobDescription jobDescription) {
        this.jobDescription = jobDescription;
    }

    public Integer getTotalFiles() {
        return totalFiles;
    }

    public void setTotalFiles(Integer totalFiles) {
        this.totalFiles = totalFiles;
    }

    public Integer getProcessedFiles() {
        return processedFiles;
    }

    public void setProcessedFiles(Integer processedFiles) {
        this.processedFiles = processedFiles;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status != null ? status : "PENDING";
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(User uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResumeBatch that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ResumeBatch{" +
                "id=" + id +
                ", batchName='" + batchName + '\'' +
                ", totalFiles=" + totalFiles +
                ", processedFiles=" + processedFiles +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}


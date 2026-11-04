package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "resume_batch_files", indexes = {
    @Index(name = "idx_batch_files_batch_id", columnList = "batch_id"),
    @Index(name = "idx_batch_files_status", columnList = "status")
})
public class ResumeBatchFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false, foreignKey = @ForeignKey(name = "fk_batch_files_batch"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ResumeBatch batch;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_hash", length = 64, columnDefinition = "CHAR(64)")
    private String fileHash;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "PENDING";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    public ResumeBatchFile() {
    }

    public ResumeBatchFile(ResumeBatch batch, String fileName, String fileHash, String filePath, String status) {
        this.batch = batch;
        this.fileName = fileName;
        this.fileHash = fileHash;
        this.filePath = filePath;
        this.status = status != null ? status : "PENDING";
    }

    public ResumeBatchFile(ResumeBatch batch, String fileName, String fileHash, String filePath, String status, String errorMessage) {
        this.batch = batch;
        this.fileName = fileName;
        this.fileHash = fileHash;
        this.filePath = filePath;
        this.status = status != null ? status : "PENDING";
        this.errorMessage = errorMessage;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ResumeBatch getBatch() {
        return batch;
    }

    public void setBatch(ResumeBatch batch) {
        this.batch = batch;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status != null ? status : "PENDING";
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
        if (!(o instanceof ResumeBatchFile that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ResumeBatchFile{" +
                "id=" + id +
                ", batchId=" + (batch != null ? batch.getId() : null) +
                ", fileName='" + fileName + '\'' +
                ", fileHash='" + fileHash + '\'' +
                ", filePath='" + filePath + '\'' +
                ", status='" + status + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}


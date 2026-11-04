package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "resumes", indexes = {
    @Index(name = "idx_resumes_candidate", columnList = "candidate_id"),
    @Index(name = "idx_resumes_status", columnList = "processing_status")
})
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false, foreignKey = @ForeignKey(name = "fk_resumes_candidate"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Candidate candidate;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_hash", length = 64, columnDefinition = "CHAR(64)")
    private String fileHash;

    @Column(name = "file_type", nullable = false, length = 100)
    private String fileType;

    @Column(name = "file_size_bytes", columnDefinition = "BIGINT UNSIGNED")
    private Long fileSizeBytes;

    @Lob
    @Column(name = "parsed_text", columnDefinition = "LONGTEXT")
    private String parsedText;

    @Column(name = "processing_status", nullable = false, length = 50)
    private String processingStatus = "PENDING";

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime uploadedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    public Resume() {
    }

    public Resume(Candidate candidate, String fileName, String filePath, String fileType,
                  Long fileSizeBytes, String parsedText, String processingStatus) {
        this.candidate = candidate;
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileType = fileType;
        this.fileSizeBytes = fileSizeBytes;
        this.parsedText = parsedText;
        this.processingStatus = processingStatus != null ? processingStatus : "PENDING";
    }

    public Resume(Candidate candidate, String fileName, String filePath, String fileHash,
                  String fileType, Long fileSizeBytes, String parsedText, String processingStatus) {
        this.candidate = candidate;
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileHash = fileHash;
        this.fileType = fileType;
        this.fileSizeBytes = fileSizeBytes;
        this.parsedText = parsedText;
        this.processingStatus = processingStatus != null ? processingStatus : "PENDING";
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Candidate getCandidate() {
        return candidate;
    }

    public void setCandidate(Candidate candidate) {
        this.candidate = candidate;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getParsedText() {
        return parsedText;
    }

    public void setParsedText(String parsedText) {
        this.parsedText = parsedText;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus != null ? processingStatus : "PENDING";
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
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
        if (!(o instanceof Resume that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Resume{" +
                "id=" + id +
                ", candidateId=" + (candidate != null ? candidate.getId() : null) +
                ", fileName='" + fileName + '\'' +
                ", filePath='" + filePath + '\'' +
                ", fileHash='" + fileHash + '\'' +
                ", fileType='" + fileType + '\'' +
                ", fileSizeBytes=" + fileSizeBytes +
                ", processingStatus='" + processingStatus + '\'' +
                ", uploadedAt=" + uploadedAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

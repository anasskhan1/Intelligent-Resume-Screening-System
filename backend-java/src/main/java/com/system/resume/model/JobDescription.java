package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "job_descriptions", indexes = {
    @Index(name = "idx_job_status", columnList = "status"),
    @Index(name = "idx_job_created", columnList = "created_at")
})
public class JobDescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "location", length = 100)
    private String location;

    @Lob
    @Column(name = "description_text", nullable = false, columnDefinition = "LONGTEXT")
    private String descriptionText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_skills", columnDefinition = "json")
    private List<String> requiredSkills = new ArrayList<>();

    @Column(name = "min_experience_years", columnDefinition = "INT UNSIGNED")
    private Integer minExperienceYears = 0;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "ACTIVE";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", foreignKey = @ForeignKey(name = "fk_job_created_by"))
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    public JobDescription() {
    }

    public JobDescription(String title, String department, String location, String descriptionText,
                          List<String> requiredSkills, Integer minExperienceYears, String status) {
        this.title = title;
        this.department = department;
        this.location = location;
        this.descriptionText = descriptionText;
        this.requiredSkills = requiredSkills != null ? requiredSkills : new ArrayList<>();
        this.minExperienceYears = minExperienceYears != null ? minExperienceYears : 0;
        this.status = status != null ? status : "ACTIVE";
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getDescriptionText() {
        return descriptionText;
    }

    public void setDescriptionText(String descriptionText) {
        this.descriptionText = descriptionText;
    }

    public List<String> getRequiredSkills() {
        return requiredSkills;
    }

    public void setRequiredSkills(List<String> requiredSkills) {
        this.requiredSkills = requiredSkills != null ? requiredSkills : new ArrayList<>();
    }

    public Integer getMinExperienceYears() {
        return minExperienceYears;
    }

    public void setMinExperienceYears(Integer minExperienceYears) {
        this.minExperienceYears = minExperienceYears != null ? minExperienceYears : 0;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status != null ? status : "ACTIVE";
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
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
        if (!(o instanceof JobDescription that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "JobDescription{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", department='" + department + '\'' +
                ", location='" + location + '\'' +
                ", minExperienceYears=" + minExperienceYears +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

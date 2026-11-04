package com.system.resume.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "refresh_tokens",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_refresh_tokens_hash", columnNames = {"token_hash"})
    },
    indexes = {
        @Index(name = "idx_refresh_tokens_family", columnList = "token_family"),
        @Index(name = "idx_refresh_tokens_user", columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_replaced", columnList = "replaced_by_token_id")
    }
)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_family", nullable = false, length = 64)
    private String tokenFamily;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_refresh_tokens_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "is_revoked", nullable = false)
    private Boolean isRevoked = false;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    @Column(name = "revoked_at", columnDefinition = "DATETIME")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_token_id", foreignKey = @ForeignKey(name = "fk_refresh_tokens_replaced"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private RefreshToken replacedBy;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    public RefreshToken() {
    }

    public RefreshToken(String tokenHash, String tokenFamily, User user, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.tokenFamily = tokenFamily;
        this.user = user;
        this.expiresAt = expiresAt;
        this.isRevoked = false;
    }

    public RefreshToken(String tokenHash, String tokenFamily, User user, LocalDateTime expiresAt, String clientIp) {
        this.tokenHash = tokenHash;
        this.tokenFamily = tokenFamily;
        this.user = user;
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
        this.isRevoked = false;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getTokenFamily() {
        return tokenFamily;
    }

    public void setTokenFamily(String tokenFamily) {
        this.tokenFamily = tokenFamily;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Boolean getIsRevoked() {
        return isRevoked;
    }

    public void setIsRevoked(Boolean isRevoked) {
        this.isRevoked = isRevoked;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Alias for {@link #getCreatedAt()} representing token issuance timestamp.
     */
    public LocalDateTime getIssuedAt() {
        return createdAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public RefreshToken getReplacedBy() {
        return replacedBy;
    }

    public void setReplacedBy(RefreshToken replacedBy) {
        this.replacedBy = replacedBy;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    /**
     * Alias for {@link #getClientIp()} representing client IP address.
     */
    public String getIpAddress() {
        return clientIp;
    }

    public void setIpAddress(String ipAddress) {
        this.clientIp = ipAddress;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return !Boolean.TRUE.equals(this.isRevoked) && !isExpired();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RefreshToken that = (RefreshToken) o;
        return Objects.equals(id, that.id) || (id == null && Objects.equals(tokenHash, that.tokenHash));
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokenHash);
    }

    @Override
    public String toString() {
        return "RefreshToken{" +
                "id=" + id +
                ", tokenHash='[PROTECTED]'" +
                ", tokenFamily='" + tokenFamily + '\'' +
                ", userId=" + (user != null ? user.getId() : null) +
                ", isRevoked=" + isRevoked +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                ", revokedAt=" + revokedAt +
                ", replacedByTokenId=" + (replacedBy != null ? replacedBy.getId() : null) +
                ", clientIp='" + clientIp + '\'' +
                '}';
    }
}


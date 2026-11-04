package com.system.resume.repository;

import com.system.resume.model.RefreshToken;
import com.system.resume.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findByUser(User user);

    List<RefreshToken> findByUserId(Long userId);

    List<RefreshToken> findByTokenFamily(String tokenFamily);

    @Query("SELECT MIN(r.createdAt) FROM RefreshToken r WHERE r.tokenFamily = :tokenFamily")
    Optional<LocalDateTime> findEarliestCreatedAtByTokenFamily(@Param("tokenFamily") String tokenFamily);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = true, r.revokedAt = :revokedAt WHERE r.tokenFamily = :tokenFamily")
    int revokeByTokenFamily(@Param("tokenFamily") String tokenFamily, @Param("revokedAt") LocalDateTime revokedAt);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = true, r.revokedAt = :revokedAt WHERE r.user.id = :userId")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("revokedAt") LocalDateTime revokedAt);
}


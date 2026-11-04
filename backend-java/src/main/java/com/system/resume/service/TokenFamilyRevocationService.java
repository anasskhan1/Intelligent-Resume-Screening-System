package com.system.resume.service;

import com.system.resume.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Dedicated service executing token family revocation within an independent
 * physical transaction (REQUIRES_NEW) to guarantee that security revocations
 * (e.g. upon reuse detection) are immediately and permanently committed to the database,
 * even when the calling transaction rolls back or throws an InvalidTokenException resulting in HTTP 401.
 */
@Service
public class TokenFamilyRevocationService {

    private static final Logger logger = LoggerFactory.getLogger(TokenFamilyRevocationService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public TokenFamilyRevocationService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Revokes all refresh tokens belonging to the specified token family
     * inside an isolated, independent physical transaction (REQUIRES_NEW).
     *
     * @param tokenFamily the token family identifier
     * @param revokedAt the revocation timestamp
     * @return the number of revoked tokens updated in the database
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeEntireFamily(String tokenFamily, LocalDateTime revokedAt) {
        int updatedCount = refreshTokenRepository.revokeByTokenFamily(tokenFamily, revokedAt);
        logger.warn("Committed immediate family revocation in separate transaction for family '{}' ({} tokens revoked)",
                tokenFamily, updatedCount);
        return updatedCount;
    }
}


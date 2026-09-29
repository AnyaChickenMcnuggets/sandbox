package com.rpatest.auth.repository;

import com.rpatest.auth.domain.RefreshToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByAppUserIdAndRevokedAtIsNull(Long appUserId);
}

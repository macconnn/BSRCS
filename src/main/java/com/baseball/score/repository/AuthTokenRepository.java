package com.baseball.score.repository;

import com.baseball.score.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByToken(String token);
}

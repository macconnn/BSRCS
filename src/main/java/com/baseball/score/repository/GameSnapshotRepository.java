package com.baseball.score.repository;

import com.baseball.score.entity.GameSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface GameSnapshotRepository extends JpaRepository<GameSnapshot, Long> {
    Optional<GameSnapshot> findFirstByGameIdOrderByActionSeqDesc(Long gameId);
    void deleteByGameId(Long gameId);
}

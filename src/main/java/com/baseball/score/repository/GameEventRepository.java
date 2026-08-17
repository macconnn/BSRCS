package com.baseball.score.repository;

import com.baseball.score.entity.GameEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GameEventRepository extends JpaRepository<GameEvent, Long> {
    List<GameEvent> findByGameIdOrderByIdDesc(Long gameId, Pageable pageable);
    List<GameEvent> findByGameIdAndActionSeqGreaterThanEqual(Long gameId, Long actionSeq);
    void deleteByGameId(Long gameId);
}

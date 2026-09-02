package com.baseball.score.repository;

import com.baseball.score.entity.GamePitcherStat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GamePitcherStatRepository extends JpaRepository<GamePitcherStat, Long> {
    Optional<GamePitcherStat> findByGameIdAndPlayerId(Long gameId, Long playerId);
    List<GamePitcherStat> findByGameId(Long gameId);
    List<GamePitcherStat> findByGameIdAndActionSeqGreaterThanEqual(Long gameId, Long actionSeq);

    /** 某位球員「所有」比賽的投手數據列，供動態計算生涯投手成績使用 */
    List<GamePitcherStat> findByPlayerId(Long playerId);

    void deleteByGameId(Long gameId);
}

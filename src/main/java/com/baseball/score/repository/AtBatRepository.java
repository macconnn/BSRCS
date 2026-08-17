package com.baseball.score.repository;

import com.baseball.score.entity.AtBat;
import com.baseball.score.enums.PlayResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AtBatRepository extends JpaRepository<AtBat, Long> {
    Optional<AtBat> findFirstByGameIdOrderBySeqNoDesc(Long gameId);
    Optional<AtBat> findFirstByGameIdAndFinishedFalseOrderBySeqNoDesc(Long gameId);

    Optional<AtBat> findFirstByGameIdAndFinishedTrueOrderBySeqNoDesc(Long gameId);
    List<AtBat> findByGameIdAndActionSeqGreaterThanEqual(Long gameId, Long actionSeq);
    List<AtBat> findByGameIdOrderBySeqNoAsc(Long gameId);

    /** 某位打者本場所有打席（球員本場表現） */
    List<AtBat> findByGameIdAndBatterLineupIdOrderBySeqNoAsc(Long gameId, Long batterLineupId);
    void deleteByGameId(Long gameId);

    /**
     * 某位球員「所有」已完成打席的結果（跨所有比賽），供動態計算生涯打擊率使用。
     * 打擊率不再是 player 表上一個寫死的欄位，而是每次查詢時由這裡的打擊紀錄即時算出。
     */
    @Query("SELECT ab.result FROM AtBat ab " +
            "WHERE ab.batterLineup.player.id = :playerId AND ab.finished = true AND ab.result IS NOT NULL")
    List<PlayResult> findResultsByPlayerId(@Param("playerId") Long playerId);
}

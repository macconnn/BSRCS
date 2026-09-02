package com.baseball.score.repository;

import com.baseball.score.entity.GameLineup;
import com.baseball.score.enums.TeamSide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface GameLineupRepository extends JpaRepository<GameLineup, Long> {
    List<GameLineup> findByGameIdAndTeamSideAndActiveTrueOrderByBattingOrderAsc(Long gameId, TeamSide side);
    List<GameLineup> findByGameIdOrderByTeamSideAscBattingOrderAsc(Long gameId);
    void deleteByGameId(Long gameId);

    /**
     * 某位球員「所有」比賽的盜壘成功次數加總（跨比賽生涯累積）。
     * 盜壘不是 PlayResult（打席結果）的一種，是獨立於打席之外的跑者事件，
     * 所以沒辦法跟安打/三振一樣從 at_bat 撈，要從 game_lineup.stolen_bases 加總。
     */
    @Query("SELECT COALESCE(SUM(l.stolenBases), 0) FROM GameLineup l WHERE l.player.id = :playerId")
    int sumStolenBasesByPlayerId(@Param("playerId") Long playerId);
}

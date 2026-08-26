package com.baseball.score.repository;

import com.baseball.score.entity.GameLineup;
import com.baseball.score.enums.TeamSide;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GameLineupRepository extends JpaRepository<GameLineup, Long> {
    List<GameLineup> findByGameIdAndTeamSideAndActiveTrueOrderByBattingOrderAsc(Long gameId, TeamSide side);
    List<GameLineup> findByGameIdOrderByTeamSideAscBattingOrderAsc(Long gameId);
    void deleteByGameId(Long gameId);
}

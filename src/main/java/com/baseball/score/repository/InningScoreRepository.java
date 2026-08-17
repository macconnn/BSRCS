package com.baseball.score.repository;

import com.baseball.score.entity.InningScore;
import com.baseball.score.enums.TeamSide;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface InningScoreRepository extends JpaRepository<InningScore, Long> {
    Optional<InningScore> findByGameIdAndTeamSideAndInning(Long gameId, TeamSide side, Integer inning);
    List<InningScore> findByGameIdOrderByInningAsc(Long gameId);
    void deleteByGameId(Long gameId);
}

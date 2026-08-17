package com.baseball.score.repository;

import com.baseball.score.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    List<Player> findByTeamIdAndActiveTrueOrderByIdAsc(Long teamId);
}

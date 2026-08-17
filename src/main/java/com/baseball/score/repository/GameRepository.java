package com.baseball.score.repository;

import com.baseball.score.entity.Game;
import com.baseball.score.enums.GameStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GameRepository extends JpaRepository<Game, Long> {
    List<Game> findByStatusOrderByGameDateDescIdDesc(GameStatus status);
    List<Game> findAllByOrderByGameDateDescIdDesc();
}

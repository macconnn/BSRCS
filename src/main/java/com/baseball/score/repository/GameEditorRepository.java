package com.baseball.score.repository;

import com.baseball.score.entity.GameEditor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameEditorRepository extends JpaRepository<GameEditor, Long> {
    boolean existsByGameIdAndUserId(Long gameId, Long userId);
}

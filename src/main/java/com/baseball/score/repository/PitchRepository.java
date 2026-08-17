package com.baseball.score.repository;

import com.baseball.score.entity.Pitch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PitchRepository extends JpaRepository<Pitch, Long> {
    List<Pitch> findByAtBatIdOrderBySeqNoDesc(Long atBatId);

    List<Pitch> findByAtBatIdOrderBySeqNoAsc(Long atBatId);
    List<Pitch> findByGameIdAndActionSeqGreaterThanEqual(Long gameId, Long actionSeq);
    void deleteByGameId(Long gameId);
}

package com.baseball.score.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 每次記錄動作前的狀態快照，用於「上一打席 / 復原」 */
@Entity
@Table(name = "game_snapshot", indexes = @Index(name = "idx_snapshot_game", columnList = "game_id,action_seq"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GameSnapshot {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(name = "action_seq", nullable = false)
    private Long actionSeq;

    @Column(name = "action_name", length = 40)
    private String actionName;

    @Column(name = "state_json", nullable = false, columnDefinition = "text")
    private String stateJson;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
}

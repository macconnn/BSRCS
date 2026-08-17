package com.baseball.score.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 誰可以編輯這場比賽（比賽建立者以外的協同記錄員） */
@Entity
@Table(name = "game_editor", uniqueConstraints =
        @UniqueConstraint(name = "uk_game_editor", columnNames = {"game_id", "user_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GameEditor {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false)
    private Long gameId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
}

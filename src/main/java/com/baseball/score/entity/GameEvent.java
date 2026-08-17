package com.baseball.score.entity;

import com.baseball.score.enums.InningHalf;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 近期賽況 feed */
@Entity
@Table(name = "game_event", indexes = @Index(name = "idx_event_game", columnList = "game_id,id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GameEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(nullable = false)
    private Integer inning;

    @Enumerated(EnumType.STRING)
    @Column(name = "inning_half", nullable = false, length = 8)
    private InningHalf half;

    /** PITCH / RESULT / INNING / SYSTEM */
    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(name = "player_name", length = 60)
    private String playerName;

    @Column(nullable = false, length = 300)
    private String description;

    /** 前端小圓點顏色：green / yellow / blue / red / gray */
    @Column(name = "color_tag", length = 12)
    private String colorTag;

    @Column(name = "action_seq", nullable = false) @Builder.Default private Long actionSeq = 0L;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
}

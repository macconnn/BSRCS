package com.baseball.score.entity;

import com.baseball.score.enums.TeamSide;
import jakarta.persistence.*;
import lombok.*;

/** 該場比賽的打線（棒次 + 守備位置） */
@Entity
@Table(name = "game_lineup", indexes = {
        @Index(name = "idx_lineup_game", columnList = "game_id"),
        @Index(name = "idx_lineup_game_side", columnList = "game_id,team_side")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GameLineup {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_side", nullable = false, length = 8)
    private TeamSide teamSide;

    /** 棒次 1~9，代打上場可與被換下者同棒次 */
    @Column(name = "batting_order", nullable = false)
    private Integer battingOrder;

    /** 守備位置：投手 / 捕手 / 一壘手 ... / 指定打擊 */
    @Column(length = 20)
    private String position;

    @Column(name = "is_starter", nullable = false) @Builder.Default private Boolean starter = true;
    @Column(nullable = false) @Builder.Default private Boolean active = true;

    /** 本場成績：打數-安打（今日成績 0-1） */
    @Column(name = "at_bats", nullable = false) @Builder.Default private Integer atBats = 0;
    @Column(nullable = false) @Builder.Default private Integer hits = 0;
    @Column(nullable = false) @Builder.Default private Integer rbi = 0;
}

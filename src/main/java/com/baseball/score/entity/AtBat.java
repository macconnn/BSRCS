package com.baseball.score.entity;

import com.baseball.score.enums.InningHalf;
import com.baseball.score.enums.PlayResult;
import com.baseball.score.enums.TeamSide;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 一個打席（打席紀錄） */
@Entity
@Table(name = "at_bat", indexes = @Index(name = "idx_atbat_game", columnList = "game_id,seq_no"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AtBat {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    /** 本場第幾個打席 */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(nullable = false)
    private Integer inning;

    @Enumerated(EnumType.STRING)
    @Column(name = "inning_half", nullable = false, length = 8)
    private InningHalf half;

    @Enumerated(EnumType.STRING)
    @Column(name = "batting_side", nullable = false, length = 8)
    private TeamSide battingSide;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batter_lineup_id", nullable = false)
    private GameLineup batterLineup;

    @Column(name = "pitcher_lineup_id")
    private Long pitcherLineupId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PlayResult result;

    @Column(nullable = false) @Builder.Default private Integer rbi = 0;
    @Column(name = "outs_recorded", nullable = false) @Builder.Default private Integer outsRecorded = 0;
    @Column(name = "runs_scored", nullable = false) @Builder.Default private Integer runsScored = 0;

    @Column(length = 300)
    private String description;

    /** 對應 game.action_seq，供復原使用 */
    @Column(name = "action_seq", nullable = false) @Builder.Default private Long actionSeq = 0L;

    @Column(name = "finished", nullable = false) @Builder.Default private Boolean finished = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
}

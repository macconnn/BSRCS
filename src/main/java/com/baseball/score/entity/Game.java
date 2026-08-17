package com.baseball.score.entity;

import com.baseball.score.enums.GameStatus;
import com.baseball.score.enums.InningHalf;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一場比賽 + 即時比賽狀態（局數、好壞球、壘包） */
@Entity
@Table(name = "game", indexes = @Index(name = "idx_game_status", columnList = "status"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Game {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 比賽名稱，例如 春季聯賽例行賽 */
    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "game_date")
    private LocalDate gameDate;

    @Column(length = 100)
    private String venue;

    @Column(length = 500)
    private String remark;

    /** 客隊＝先攻（上半局進攻），畫面左側藍隊 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "away_team_id", nullable = false)
    private Team awayTeam;

    /** 主隊＝後攻（下半局進攻），畫面右側紅隊 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_team_id", nullable = false)
    private Team homeTeam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private GameStatus status = GameStatus.SCHEDULED;

    @Column(name = "total_innings", nullable = false)
    @Builder.Default
    private Integer totalInnings = 9;

    // ---------- 即時狀態 ----------
    @Column(nullable = false) @Builder.Default private Integer inning = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "inning_half", nullable = false, length = 8)
    @Builder.Default
    private InningHalf half = InningHalf.TOP;

    @Column(nullable = false) @Builder.Default private Integer outs = 0;
    @Column(nullable = false) @Builder.Default private Integer balls = 0;
    @Column(nullable = false) @Builder.Default private Integer strikes = 0;

    /** 壘包上的跑者（存 game_lineup.id），null = 無人 */
    @Column(name = "runner_first")  private Long runnerFirst;
    @Column(name = "runner_second") private Long runnerSecond;
    @Column(name = "runner_third")  private Long runnerThird;

    @Column(name = "away_score", nullable = false)  @Builder.Default private Integer awayScore = 0;
    @Column(name = "home_score", nullable = false)  @Builder.Default private Integer homeScore = 0;
    @Column(name = "away_hits", nullable = false)   @Builder.Default private Integer awayHits = 0;
    @Column(name = "home_hits", nullable = false)   @Builder.Default private Integer homeHits = 0;
    @Column(name = "away_errors", nullable = false) @Builder.Default private Integer awayErrors = 0;
    @Column(name = "home_errors", nullable = false) @Builder.Default private Integer homeErrors = 0;

    /** 目前打到打線第幾棒（0-based index） */
    @Column(name = "away_batter_index", nullable = false) @Builder.Default private Integer awayBatterIndex = 0;
    @Column(name = "home_batter_index", nullable = false) @Builder.Default private Integer homeBatterIndex = 0;

    /** 目前投手（game_lineup.id） */
    @Column(name = "away_pitcher_lineup_id") private Long awayPitcherLineupId;
    @Column(name = "home_pitcher_lineup_id") private Long homePitcherLineupId;

    /** 每執行一個動作 +1，用於還原（上一打席 / 復原） */
    @Column(name = "action_seq", nullable = false) @Builder.Default private Long actionSeq = 0L;

    @Column(name = "created_by") private Long createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default private LocalDateTime updatedAt = LocalDateTime.now();
}

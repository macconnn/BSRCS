package com.baseball.score.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 「這場比賽 × 這位投手」的即時累積投手數據。
 * 換投手時（GameService.syncPitcherPointer 更新 game.away/homePitcherLineupId 之後），
 * 後續投球 / 打席結果 / 盜壘會依當下 game.away/homePitcherLineupId（或該打席建立當下記錄的
 * at_bat.pitcher_lineup_id）指到的球員，累加到「這位球員在這場比賽」對應的這一列，
 * 而不是寫死累加在原本先發投手身上，藉此讓救援投手上場後的數據，自動記到救援投手自己身上。
 *
 * 每場比賽、每位球員最多一列（uk_game_pitcher_stat），同一位投手中途退場又重新上場，
 * 數據會累加在同一列上。
 */
@Entity
@Table(name = "game_pitcher_stat", uniqueConstraints =
        @UniqueConstraint(name = "uk_game_pitcher_stat", columnNames = {"game_id", "player_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GamePitcherStat {

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

    /** 這場比賽中，這位投手總共記錄了幾個出局數（局數＝outs/3，用棒球慣例格式顯示，例如 16 個出局 → 5.1 局） */
    @Column(name = "innings_outs", nullable = false) @Builder.Default private Integer inningsOuts = 0;

    /** 投球數 */
    @Column(nullable = false) @Builder.Default private Integer pitches = 0;

    /** 失分（不分自責分 / 非自責分，單純以「這位投手在守備時對方得的分數」計算） */
    @Column(name = "runs_allowed", nullable = false) @Builder.Default private Integer runsAllowed = 0;

    /** 被安打（含被二壘安打 / 三壘安打 / 全壘打，這三項是被安打的子集） */
    @Column(name = "hits_allowed", nullable = false) @Builder.Default private Integer hitsAllowed = 0;
    @Column(name = "doubles_allowed", nullable = false) @Builder.Default private Integer doublesAllowed = 0;
    @Column(name = "triples_allowed", nullable = false) @Builder.Default private Integer triplesAllowed = 0;
    @Column(name = "home_runs_allowed", nullable = false) @Builder.Default private Integer homeRunsAllowed = 0;

    @Column(name = "walks_allowed", nullable = false) @Builder.Default private Integer walksAllowed = 0;
    @Column(name = "hit_by_pitch_allowed", nullable = false) @Builder.Default private Integer hitByPitchAllowed = 0;
    @Column(name = "stolen_bases_allowed", nullable = false) @Builder.Default private Integer stolenBasesAllowed = 0;

    /** 對應 game.action_seq，供復原（undo）使用：判斷這一列是否是「要復原的那個動作」才新建的 */
    @Column(name = "action_seq", nullable = false) @Builder.Default private Long actionSeq = 0L;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
}

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

    /**
     * 本場盜壘成功 / 被阻殺次數。
     * 注意：columnDefinition 明確加上 "default 0"，是因為這兩個欄位是後來才加到既有的 game_lineup 表格，
     * 表格裡已經有資料列了。如果只靠 nullable = false 沒有 default，Hibernate ddl-auto=update 產生的
     * ALTER TABLE ... ADD COLUMN ... NOT NULL 會因為既有資料列沒有值而失敗
     * （PostgreSQL 錯誤：column contains null values）。有 default 0，新增欄位時舊資料列會自動補上 0，
     * 這個 ALTER TABLE 才能成功。@Builder.Default 只影響 Java 端的預設值，不會反映到 DDL 上，別搞混。
     */
    @Column(name = "stolen_bases", nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default private Integer stolenBases = 0;
    @Column(name = "caught_stealing", nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default private Integer caughtStealing = 0;
}

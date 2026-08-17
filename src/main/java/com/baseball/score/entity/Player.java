package com.baseball.score.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "player", indexes = @Index(name = "idx_player_team", columnList = "team_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Player {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(nullable = false, length = 60)
    private String name;

    /** 背號 */
    @Column(name = "jersey_number", length = 5)
    private String jerseyNumber;

    /** 慣用守備位置，例如 中外野手 */
    @Column(name = "default_position", length = 20)
    private String defaultPosition;

    /**
     * 注意：打擊率「不」存在這裡。
     * 打擊率一律由 {@link com.baseball.score.service.PlayerStatsService} 依 at_bat
     * （所有打擊紀錄）動態加總計算，新球員預設 0 成績、隨紀錄員每次記錄安打/三振等結果即時變動，
     * 不再是可手動輸入、寫死的欄位，避免資料與實際比賽紀錄不一致。
     */

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}

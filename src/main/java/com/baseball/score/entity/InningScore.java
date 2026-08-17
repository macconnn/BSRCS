package com.baseball.score.entity;

import com.baseball.score.enums.TeamSide;
import jakarta.persistence.*;
import lombok.*;

/** 各局得分（記分板 1~9 局） */
@Entity
@Table(name = "inning_score", uniqueConstraints =
        @UniqueConstraint(name = "uk_inning_score", columnNames = {"game_id", "team_side", "inning"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InningScore {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_side", nullable = false, length = 8)
    private TeamSide teamSide;

    @Column(nullable = false)
    private Integer inning;

    @Column(nullable = false) @Builder.Default private Integer runs = 0;
    @Column(nullable = false) @Builder.Default private Integer hits = 0;
    @Column(nullable = false) @Builder.Default private Integer errors = 0;
}

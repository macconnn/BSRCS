package com.baseball.score.entity;

import com.baseball.score.enums.PitchCall;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 投球紀錄（本打席） */
@Entity
@Table(name = "pitch", indexes = @Index(name = "idx_pitch_atbat", columnList = "at_bat_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Pitch {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "at_bat_id", nullable = false)
    private AtBat atBat;

    /** 本打席第幾球 */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_type", nullable = false, length = 16)
    private PitchCall call;

    /** 球種：直球 / 曲球 / 滑球 / 變速球 ... */
    @Column(name = "pitch_type", length = 20)
    private String pitchType;

    @Column(name = "speed_kmh")
    private Integer speedKmh;

    @Column(name = "balls_after", nullable = false) @Builder.Default private Integer ballsAfter = 0;
    @Column(name = "strikes_after", nullable = false) @Builder.Default private Integer strikesAfter = 0;

    @Column(name = "action_seq", nullable = false) @Builder.Default private Long actionSeq = 0L;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
}

package com.baseball.score.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "team")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Team {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "short_name", length = 20)
    private String shortName;

    /** 顯示色，例如 #1a56db（藍隊）/ #dc2626（紅隊） */
    @Column(name = "color_hex", length = 10)
    private String colorHex;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

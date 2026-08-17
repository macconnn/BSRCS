package com.baseball.score.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 登入後發出的 token（存於 cookie，取代 Spring Security session） */
@Entity
@Table(name = "auth_token", indexes = @Index(name = "idx_auth_token_token", columnList = "token", unique = true))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuthToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "user_agent", length = 300)
    private String userAgent;
}

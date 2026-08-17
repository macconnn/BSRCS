package com.baseball.score.entity;

import com.baseball.score.enums.OtpPurpose;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** OTP 驗證碼（JavaMail 寄送） */
@Entity
@Table(name = "otp_code", indexes = {
        @Index(name = "idx_otp_email", columnList = "email"),
        @Index(name = "idx_otp_expires", columnList = "expires_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtpCode {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String email;

    /** 6 碼數字驗證碼 */
    @Column(nullable = false, length = 10)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private OtpPurpose purpose = OtpPurpose.LOGIN;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 已使用時間，null = 尚未使用 */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "request_ip", length = 45)
    private String requestIp;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

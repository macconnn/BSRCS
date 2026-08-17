package com.baseball.score.service;

import com.baseball.score.config.AppProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/** JavaMail 寄送 OTP 驗證碼；app.mail.enabled=false 時只印在 log（本機開發用）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final AppProperties props;

    public void sendOtp(String to, String code, int ttlMinutes) {
        if (!props.getMail().isEnabled()) {
            log.warn("[DEV MAIL] 寄給 {} 的登入驗證碼 = {}（{} 分鐘內有效）", to, code, ttlMinutes);
            return;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.error("JavaMailSender 未設定，無法寄送驗證碼給 {}", to);
            throw new IllegalStateException("郵件服務尚未設定");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(props.getMail().getFrom());
            helper.setTo(to);
            helper.setSubject(props.getMail().getSubject());
            helper.setText(buildHtml(code, ttlMinutes), true);
            sender.send(message);
            log.info("驗證碼已寄出：{}", to);
        } catch (Exception e) {
            log.error("寄送驗證碼失敗：{}", to, e);
            throw new IllegalStateException("驗證碼寄送失敗，請稍後再試");
        }
    }

    private String buildHtml(String code, int ttlMinutes) {
        return """
            <div style="font-family:'Noto Sans TC',Arial,sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;">
              <h2 style="color:#0f172a;margin:0 0 8px;">線上棒球比賽紀錄表</h2>
              <p style="color:#64748b;margin:0 0 24px;">請輸入以下驗證碼完成編輯者登入</p>
              <div style="background:#f1f5f9;border-radius:12px;padding:20px;text-align:center;">
                <span style="font-size:34px;letter-spacing:10px;font-weight:700;color:#1d4ed8;">%s</span>
              </div>
              <p style="color:#64748b;font-size:13px;margin-top:20px;">
                驗證碼將於 %d 分鐘後失效。若不是您本人操作，請忽略這封信。
              </p>
            </div>
            """.formatted(code, ttlMinutes);
    }
}

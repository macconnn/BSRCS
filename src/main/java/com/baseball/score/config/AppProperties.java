package com.baseball.score.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private boolean seed = false;
    private String defaultEditorEmail;
    private Otp otp = new Otp();
    private Auth auth = new Auth();
    private Mail mail = new Mail();

    @Getter @Setter
    public static class Otp {
        private int length = 6;
        private int ttlMinutes = 10;
        private int resendIntervalSeconds = 60;
        private int maxAttempts = 5;
    }

    @Getter @Setter
    public static class Auth {
        private String cookieName = "BB_TOKEN";
        private int tokenTtlDays = 7;
    }

    @Getter @Setter
    public static class Mail {
        private boolean enabled = true;
        private String from = "no-reply@baseball.local";
        private String subject = "【線上棒球比賽紀錄表】登入驗證碼";
    }
}

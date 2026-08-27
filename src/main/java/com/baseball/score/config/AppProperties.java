package com.baseball.score.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private boolean seed = false;
    private String defaultEditorEmail;
    /** App 版本號，只顯示在登入頁角落，方便確認目前部署的是哪個版本 */
    private String version = "v1.1.3";
    /**
     * 是否開放記錄「球種」與「球速」。
     * true（預設）：球數紀錄卡片會顯示球種下拉選單、球速輸入框，記錄員可以自行選填。
     * false：畫面上完全不顯示這兩個欄位，記錄一球時後端一律用預設值「直球」／100 km/h。
     */
    private boolean pitchDetailEnabled = true;
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

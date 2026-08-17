package com.baseball.score.service;

import com.baseball.score.config.AppProperties;
import com.baseball.score.config.CurrentUser;
import com.baseball.score.entity.AppUser;
import com.baseball.score.entity.AuthToken;
import com.baseball.score.entity.OtpCode;
import com.baseball.score.enums.OtpPurpose;
import com.baseball.score.enums.Role;
import com.baseball.score.repository.AppUserRepository;
import com.baseball.score.repository.AuthTokenRepository;
import com.baseball.score.repository.OtpCodeRepository;
import com.baseball.score.util.ApiException;
import com.baseball.score.util.DeviceUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 登入流程（不使用 Spring Security）：
 *   1. requestOtp()  → 產生驗證碼寫進 otp_code，JavaMail 寄出
 *   2. verifyOtp()   → 驗證成功建立 app_user（若不存在）＋ auth_token，token 寫進 cookie
 *   3. resolve()     → 每個 request 由 AuthInterceptor 呼叫，解析出 EDITOR 或 VIEWER
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpCodeRepository otpRepo;
    private final AppUserRepository userRepo;
    private final AuthTokenRepository tokenRepo;
    private final MailService mailService;
    private final AppProperties props;

    // ---------------------------------------------------------------- OTP

    @Transactional
    public int requestOtp(String rawEmail, String ip) {
        String email = normalize(rawEmail);
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException("Email 格式不正確");
        }
        // 重送冷卻
        otpRepo.findFirstByEmailIgnoreCaseOrderByIdDesc(email).ifPresent(last -> {
            long secondsSince = Duration.between(last.getCreatedAt(), LocalDateTime.now()).getSeconds();
            int interval = props.getOtp().getResendIntervalSeconds();
            if (secondsSince < interval) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "驗證碼剛剛已寄出，請於 " + (interval - secondsSince) + " 秒後再試");
            }
        });

        otpRepo.consumeAll(email, LocalDateTime.now());

        String code = randomCode(props.getOtp().getLength());
        int ttl = props.getOtp().getTtlMinutes();
        otpRepo.save(OtpCode.builder()
                .email(email)
                .code(code)
                .purpose(OtpPurpose.LOGIN)
                .expiresAt(LocalDateTime.now().plusMinutes(ttl))
                .requestIp(ip)
                .build());

        mailService.sendOtp(email, code, ttl);
        return ttl;
    }

    /**
     * 暫時簡化版登入：驗證 Email 格式，並確認 app_user 資料表中已存在此 Email，
     * 存在才允許登入（不存在的 Email 不會自動建立新帳號）。不寄送、也不檢查驗證碼。
     * 原本的 requestOtp() / verifyOtp() 流程保留在下方，之後要恢復兩步驟驗證碼登入，
     * 把 Controller 改回呼叫 requestOtp()/verifyOtp() 即可。
     */
    @Transactional
    public String loginByEmailOnly(String rawEmail, String userAgent) {
        String email = normalize(rawEmail);
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException("Email 格式不正確");
        }

        // 只允許已存在的帳號登入，不存在則直接擋下（不再自動建立新帳號）
        AppUser user = userRepo.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "此 Email 尚未註冊，請聯絡管理員新增帳號"));

        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "此帳號已被停用");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepo.save(user);

        AuthToken token = tokenRepo.save(AuthToken.builder()
                .token(UUID.randomUUID().toString().replace("-", ""))
                .user(user)
                .expiresAt(LocalDateTime.now().plusDays(props.getAuth().getTokenTtlDays()))
                .userAgent(userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 290)))
                .build());
        return token.getToken();
    }

    @Transactional
    public String verifyOtp(String rawEmail, String code, String userAgent) {
        String email = normalize(rawEmail);
        OtpCode otp = otpRepo
                .findFirstByEmailIgnoreCaseAndPurposeAndConsumedAtIsNullOrderByIdDesc(email, OtpPurpose.LOGIN)
                .orElseThrow(() -> new ApiException("尚未取得驗證碼，請先點選「發送驗證碼」"));

        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException("驗證碼已失效，請重新發送");
        }
        if (otp.getAttemptCount() >= props.getOtp().getMaxAttempts()) {
            throw new ApiException("錯誤次數過多，請重新發送驗證碼");
        }
        if (!otp.getCode().equals(StringUtils.trimAllWhitespace(code))) {
            otp.setAttemptCount(otp.getAttemptCount() + 1);
            otpRepo.save(otp);
            int left = props.getOtp().getMaxAttempts() - otp.getAttemptCount();
            throw new ApiException("驗證碼不正確，還可以嘗試 " + Math.max(left, 0) + " 次");
        }

        otp.setConsumedAt(LocalDateTime.now());
        otpRepo.save(otp);

        AppUser user = userRepo.findByEmailIgnoreCase(email).orElseGet(() -> userRepo.save(
                AppUser.builder()
                        .email(email)
                        .displayName(email.substring(0, email.indexOf('@')))
                        .role(Role.EDITOR)
                        .enabled(true)
                        .build()));
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "此帳號已被停用");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepo.save(user);

        AuthToken token = tokenRepo.save(AuthToken.builder()
                .token(UUID.randomUUID().toString().replace("-", ""))
                .user(user)
                .expiresAt(LocalDateTime.now().plusDays(props.getAuth().getTokenTtlDays()))
                .userAgent(userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 290)))
                .build());
        return token.getToken();
    }

    // ---------------------------------------------------------------- session

    @Transactional(readOnly = true)
    public CurrentUser resolve(HttpServletRequest request) {
        String token = readCookie(request);
        if (!StringUtils.hasText(token)) return CurrentUser.viewer();

        Optional<AuthToken> opt = tokenRepo.findByToken(token);
        if (opt.isEmpty()) return CurrentUser.viewer();

        AuthToken at = opt.get();
        if (at.getRevokedAt() != null || at.getExpiresAt().isBefore(LocalDateTime.now())) {
            return CurrentUser.viewer();
        }
        AppUser user = at.getUser();
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) return CurrentUser.viewer();

        return new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String token = readCookie(request);
        if (StringUtils.hasText(token)) {
            tokenRepo.findByToken(token).ifPresent(t -> {
                t.setRevokedAt(LocalDateTime.now());
                tokenRepo.save(t);
            });
        }
        writeCookie(response, "", 0);
    }

    public void writeCookie(HttpServletResponse response, String token, int maxAgeSeconds) {
        Cookie cookie = new Cookie(props.getAuth().getCookieName(), token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        response.addCookie(cookie);
    }

    public int cookieMaxAge() {
        return props.getAuth().getTokenTtlDays() * 24 * 60 * 60;
    }

    public String clientIp(HttpServletRequest request) {
        return DeviceUtil.clientIp(request);
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (props.getAuth().getCookieName().equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }
}

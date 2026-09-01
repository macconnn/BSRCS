package com.baseball.score.service;

import com.baseball.score.entity.AppUser;
import com.baseball.score.enums.Role;
import com.baseball.score.repository.AppUserRepository;
import com.baseball.score.util.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 帳號管理：僅供 ADMIN 使用（Controller 層已經用 @RequireAdmin 擋掉非管理員）。
 * 目前的登入流程（見 AuthService.loginByEmailOnly）只允許「已存在於 app_user 的 Email」登入，
 * 不開放自行註冊，所以新增帳號一定要透過這裡由管理員手動建立。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$");

    private final AppUserRepository userRepo;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers() {
        return userRepo.findAll().stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    @Transactional
    public AppUser createUser(String rawEmail, String displayName, Role role) {
        String email = normalize(rawEmail);
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException("Email 格式不正確");
        }
        if (!StringUtils.hasText(displayName)) {
            throw new ApiException("請輸入顯示名稱");
        }
        if (role == null) {
            throw new ApiException("請選擇角色");
        }
        if (userRepo.findByEmailIgnoreCase(email).isPresent()) {
            throw new ApiException("這個 Email 已經有帳號了");
        }

        return userRepo.save(AppUser.builder()
                .email(email)
                .displayName(displayName.trim())
                .role(role)
                .enabled(true)
                .build());
    }

    @Transactional
    public AppUser updateUser(Long userId, String displayName, String rawEmail, Role role, Long actingAdminUserId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到這個帳號"));

        if (!StringUtils.hasText(displayName)) {
            throw new ApiException("請輸入顯示名稱");
        }
        String email = normalize(rawEmail);
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException("Email 格式不正確");
        }
        if (role == null) {
            throw new ApiException("請選擇角色");
        }

        // Email 不能撞到別人已經在用的（自己不算撞到）
        userRepo.findByEmailIgnoreCase(email).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new ApiException("這個 Email 已經被其他帳號使用");
            }
        });

        // 防呆：管理員不能把自己目前登入的帳號角色改成非 ADMIN，避免把自己降級後失去管理權限，
        // 又剛好沒有其他 ADMIN 帳號可以救援（跟 setEnabled() 裡「不能停用自己」是同一個考量）。
        if (userId.equals(actingAdminUserId) && role != Role.ADMIN) {
            throw new ApiException("不能把自己目前登入的帳號角色改成非管理員");
        }

        user.setDisplayName(displayName.trim());
        user.setEmail(email);
        user.setRole(role);
        return userRepo.save(user);
    }

    @Transactional
    public AppUser setEnabled(Long userId, boolean enabled, Long actingAdminUserId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到這個帳號"));

        // 防呆：管理員不能把自己停用，避免不小心把自己鎖在外面、之後沒有其他管理員可以救援。
        if (!enabled && userId.equals(actingAdminUserId)) {
            throw new ApiException("不能停用自己目前登入的這個帳號");
        }

        user.setEnabled(enabled);
        return userRepo.save(user);
    }

    private Map<String, Object> toMap(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("email", u.getEmail());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole().name());
        m.put("enabled", u.getEnabled());
        m.put("createdAt", u.getCreatedAt());
        m.put("lastLoginAt", u.getLastLoginAt());
        return m;
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}

package com.baseball.score.config;

import com.baseball.score.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 每個 request 解析出來的身分；沒有 token 時就是 VIEWER。 */
@Getter
@AllArgsConstructor
public class CurrentUser {

    private final Long userId;
    private final String email;
    private final String displayName;
    private final Role role;

    public static CurrentUser viewer() {
        return new CurrentUser(null, null, "瀏覽者", Role.VIEWER);
    }

    public boolean isLoggedIn() { return userId != null; }

    public boolean canEdit() { return role == Role.EDITOR || role == Role.ADMIN; }

    public boolean isAdmin() { return role == Role.ADMIN; }
}

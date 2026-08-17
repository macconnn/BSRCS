package com.baseball.score.enums;

/** 只有 EDITOR / ADMIN 需要登入；沒有 token 的一律視為 VIEWER（瀏覽者）。 */
public enum Role { VIEWER, EDITOR, ADMIN }

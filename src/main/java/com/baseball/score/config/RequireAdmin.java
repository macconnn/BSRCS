package com.baseball.score.config;

import java.lang.annotation.*;

/** 標在 Controller method / class 上，攔截器會擋掉非管理員（ADMIN）身分的請求。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {
}

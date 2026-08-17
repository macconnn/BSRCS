package com.baseball.score.config;

import java.lang.annotation.*;

/** 標在 Controller method / class 上，攔截器會擋掉非編輯者（取代 Spring Security）。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireEditor {
}

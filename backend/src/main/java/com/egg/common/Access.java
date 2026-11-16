package com.egg.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在控制器类或方法上，写明谁能调（开发规格 5.2 接口表的“谁”一列）。方法上的盖过类上的。
 * 不标就是仅管理员——新接口漏标时宁可调不通，也不要默认放开。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Access {

    Who value();

    enum Who {
        /** 表里的 `—`：无需登录 */
        PUBLIC,
        /** 表里的 `A` */
        ADMIN,
        /** 表里的 `S` */
        SCALE,
        /** 表里的 `A+S` */
        ADMIN_OR_SCALE
    }
}

package com.egg.pricing;

import java.math.RoundingMode;

/** 均重取整规则（v7 2.2）。选项 round.mode 的取值是小写，单据上冻结的 round_mode 是大写，这里两种都认。 */
public enum RoundMode {
    /** 四舍五入，小数部分 ≥ 0.5 进 1 */
    HALF(RoundingMode.HALF_UP),
    /** 抹零 */
    FLOOR(RoundingMode.FLOOR),
    /** 进位，有小数即进 1 */
    CEIL(RoundingMode.CEILING);

    final RoundingMode mode;

    RoundMode(RoundingMode mode) {
        this.mode = mode;
    }

    public static RoundMode parse(String s) {
        return valueOf(s.trim().toUpperCase());
    }
}

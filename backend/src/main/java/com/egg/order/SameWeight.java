package com.egg.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同重归类（v7 5.3、6.3）：按记录值归并，不另行舍入；箱数多的在前，箱数相同的按首次出现的先后。
 * 流水页与回传备注共用这一处。
 */
public final class SameWeight {

    /** @param jin 每箱净重，去掉末尾的 0（46.00 与 46 是同一个值） */
    public record Group(BigDecimal jin, int boxes) {
    }

    private SameWeight() {
    }

    /** @param netJin 按批次序号排好的各箱净重 */
    public static List<Group> of(List<BigDecimal> netJin) {
        Map<BigDecimal, Integer> counts = new LinkedHashMap<>();
        for (BigDecimal n : netJin) {
            counts.merge(plain(n), 1, Integer::sum);
        }
        List<Group> out = new ArrayList<>();
        counts.forEach((jin, n) -> out.add(new Group(jin, n)));
        // List.sort 是稳定排序，并列的保持首次出现的顺序
        out.sort((a, b) -> Integer.compare(b.boxes(), a.boxes()));
        return out;
    }

    /** 去掉末尾的 0，但不留负的标度：100 不能变成 1E+2，否则序列化出来是科学计数法。 */
    private static BigDecimal plain(BigDecimal n) {
        BigDecimal s = n.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}

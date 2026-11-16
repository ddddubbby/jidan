package com.egg.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回传好生意的备注（v7 5.3、开发规格 3.5）。全系统只在这里组装；APP 另有一份只用于流水页展示，
 * 两边共用 fixtures/remark.json。
 * <p>
 * 全文：{@code 共N箱；逐箱：46.5,45.8,…；归类：45.6kg-4箱,46kg-3箱,…}。归类按箱数多的在前，箱数相同按首次出现的箱序号，
 * 按记录值（含手录值）归并，不另行舍入；重量去掉末尾的 0。手录记号不写入备注。
 */
public final class Remark {

    /** kg 为每箱净重，boxes 为箱数。 */
    public record Group(BigDecimal kg, int boxes) {
    }

    /** truncated：全文超过长度上限，已降为只留“共N箱”与归类段。 */
    public record Result(String text, boolean truncated, List<Group> groups) {
    }

    private Remark() {
    }

    /** 全文；超过 maxLength（选项 push.remark-max-length，0 不限）时自动降为汇总。 */
    public static Result build(List<BigDecimal> boxNetKg, int maxLength) {
        List<Group> groups = groups(boxNetKg);
        String each = "逐箱：" + String.join(",", boxNetKg.stream().map(Remark::kg).toList());
        String full = head(boxNetKg) + "；" + each + "；" + sorted(groups);
        if (maxLength > 0 && full.length() > maxLength) {
            return new Result(summaryText(boxNetKg, groups), true, groups);
        }
        return new Result(full, false, groups);
    }

    /** 选项 push.remark = summary：不论长度，只带“共N箱”与归类段。 */
    public static Result summary(List<BigDecimal> boxNetKg) {
        List<Group> groups = groups(boxNetKg);
        return new Result(summaryText(boxNetKg, groups), false, groups);
    }

    private static String head(List<BigDecimal> boxNetKg) {
        return "共" + boxNetKg.size() + "箱";
    }

    private static String summaryText(List<BigDecimal> boxNetKg, List<Group> groups) {
        return head(boxNetKg) + "；" + sorted(groups);
    }

    private static String sorted(List<Group> groups) {
        return "归类：" + String.join(",", groups.stream().map(g -> kg(g.kg()) + "kg-" + g.boxes() + "箱").toList());
    }

    private static List<Group> groups(List<BigDecimal> boxNetKg) {
        // 先按首次出现的顺序归并；下面的排序是稳定的，所以箱数相同的仍按首次出现排
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (BigDecimal w : boxNetKg) {
            String key = kg(w);
            counts.merge(key, 1, Integer::sum);
            weights.putIfAbsent(key, w.stripTrailingZeros());
        }
        List<Group> out = new ArrayList<>();
        counts.forEach((key, n) -> out.add(new Group(weights.get(key), n)));
        out.sort((a, b) -> Integer.compare(b.boxes(), a.boxes()));
        return out;
    }

    /** 46.00 写成 46，45.60 写成 45.6；不用科学计数法。 */
    static String kg(BigDecimal w) {
        return w.stripTrailingZeros().toPlainString();
    }
}

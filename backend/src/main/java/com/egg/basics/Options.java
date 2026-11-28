package com.egg.basics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.egg.common.ApiException;

/**
 * 选项注册表（开发规格第 4 章）：业务设置与高级选项的定义与默认值都只在这里。每个选项一行，
 * 值存在 option 表里，没存过的取默认值。加一个选项只有三步：这里加一行 → 在用到它的地方读它 → 补测试或走查。
 * <p>
 * 值一律按字符串存取；{@link Def#normalize} 负责校验并规整，接口入口之外不再重复校验。
 */
public final class Options {

    public enum Group {
        /** v7 WEB-09 的 8 项全局业务设置，在系统设置页维护 */
        SETTING("系统设置"),
        /** 第一组：设备与接口适配，联调时由工程师定下来 */
        ADAPTER("设备与接口适配"),
        /** 第二组：作业策略，随现场情况调整 */
        POLICY("作业策略");

        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    public enum Type {
        ENUM, INT, DECIMAL
    }

    public record Choice(String value, String label) {
    }

    /**
     * @param hint 一句“什么时候改”（高级选项页直接显示）
     * @param min  数值型的下限，可空
     * @param max  数值型的上限，可空
     */
    public record Def(String key, Group group, Type type, String name, String hint, List<Choice> choices,
            String min, String max, String defaultValue) {

        /** 校验并规整一个用户输入的值：枚举必须是可选值之一；数值必须是数且在范围内。 */
        public String normalize(String raw) {
            String v = raw == null ? "" : raw.trim();
            switch (type) {
                case ENUM -> {
                    if (choices.stream().noneMatch(c -> c.value().equals(v))) {
                        throw ApiException.invalid(name + "不支持“" + v + "”，可选：" + choices.stream()
                                .map(Choice::value).reduce((a, b) -> a + "、" + b).orElse(""));
                    }
                    return v;
                }
                case INT -> {
                    BigDecimal n = number(v);
                    if (n.stripTrailingZeros().scale() > 0) {
                        throw ApiException.invalid(name + "必须是整数");
                    }
                    inRange(n);
                    return n.toBigInteger().toString();
                }
                default -> {
                    BigDecimal n = number(v);
                    inRange(n);
                    return n.stripTrailingZeros().toPlainString();
                }
            }
        }

        private BigDecimal number(String v) {
            try {
                return new BigDecimal(v);
            } catch (NumberFormatException e) {
                throw ApiException.invalid(name + "必须是数字");
            }
        }

        private void inRange(BigDecimal n) {
            if (min != null && n.compareTo(new BigDecimal(min)) < 0
                    || max != null && n.compareTo(new BigDecimal(max)) > 0) {
                throw ApiException.invalid(name + "要在 " + (min == null ? "不限" : min) + " 到 "
                        + (max == null ? "不限" : max) + " 之间");
            }
        }
    }

    private static final Choice[] ON_OFF = {new Choice("on", "开"), new Choice("off", "关")};
    private static final Choice[] TRUE_FALSE = {new Choice("true", "是"), new Choice("false", "否")};

    /** 登记顺序就是页面显示顺序。 */
    private static final List<Def> ALL = new ArrayList<>();

    static {
        // ---- 业务设置：v7 WEB-09 的 8 项（标签纸尺寸占宽、高、间隙三行）----
        enumOf("round.mode", Group.SETTING, "均重取整", "修改后新建的单据与待审核的单据按新规则算均重", "half",
                c("half", "四舍五入"), c("floor", "抹零"), c("ceil", "进位"));
        enumOf("print.timing", Group.SETTING, "标签打印方式", "逐箱：记一箱打一张；完成后：确认计量完毕后依次打", "per-box",
                c("per-box", "逐箱打印"), c("after-confirm", "完成后一次打印"));
        enumOf("invoice.type", Group.SETTING, "开票类型", "回传好生意时填入 noteTypeEnum，取审核那一刻的值", "NO_INVOICE",
                c("NO_INVOICE", "不开票"), c("GENERAL", "普通发票"), c("SPECIAL", "专用发票"));
        number("invoice.tax-rate", Group.SETTING, Type.DECIMAL, "税率（%）", "回传好生意时填入 taxPct，取审核那一刻的值",
                "0", "100", "0");
        number("label.width-mm", Group.SETTING, Type.DECIMAL, "标签纸宽（mm）", "拿到实际标签纸后调整", "10", "200", "60");
        number("label.height-mm", Group.SETTING, Type.DECIMAL, "标签纸高（mm）", "拿到实际标签纸后调整", "10", "200", "40");
        number("label.gap-mm", Group.SETTING, Type.DECIMAL, "标签纸间隙（mm）", "拿到实际标签纸后调整", "0", "20", "2");
        enumOf("label.content", Group.SETTING, "标签内容", "精简：净重、批次、批次序号、二维码；完整：v7 5.4 的 9 项。两种都不印均重", "minimal",
                c("minimal", "精简"), c("full", "完整"));
        // 可选值就是实机版里已有适配器的型号；加一个品牌 = 加一个适配器 + 这里加一个可选值（开发规格 4.4）
        enumOf("device.scale-model", Group.SETTING, "电子秤型号", "APP 按所选型号连接对应的秤", "lanstar-ble",
                c("lanstar-ble", "蓝石 BLE 电子秤"));
        enumOf("device.printer-model", Group.SETTING, "打印机型号", "APP 按所选型号连接对应的打印机", "gainscha-gp-m32b",
                c("gainscha-gp-m32b", "佳博 GP-M32B"));

        // ---- 第一组：设备与接口适配 ----
        // 业务上全系统用斤；秤给的读数是什么单位没实测过，默认按 kg，APP 收到读数先换成斤（附录 E R-1 行）
        enumOf("scale.unit", Group.ADAPTER, "秤读数单位", "秤显示和上传的是斤而不是公斤时改成斤", "kg",
                c("kg", "公斤（APP 乘 2 换成斤）"), c("jin", "斤（不换算）"));
        enumOf("scale.tare-by", Group.ADAPTER, "去皮方式", "秤的去皮接口不好用，或读数含义搞不清", "scale",
                c("scale", "皮重下发到秤"), c("app", "APP 自己扣皮"));
        // v7 7.2、Q-03 未确认；默认按“SDK 设皮后读数已是净重”，实测不是时改成否
        enumOf("scale.reading-is-net", Group.ADAPTER, "秤读数已扣皮", "实测秤的读数未扣皮（Q-03）。只在“去皮方式”为皮重下发到秤时有意义",
                "true", TRUE_FALSE);
        enumOf("scale.stable-by", Group.ADAPTER, "稳定判断", "秤的稳定标志不可靠", "sdk",
                c("sdk", "用秤给的稳定标志"), c("app", "APP 按读数是否不动判断"));
        number("scale.stable-window-ms", Group.ADAPTER, Type.INT, "稳定窗口（毫秒）", "“稳定判断”为 APP 判断时，记得太早或太晚",
                "100", "10000", "800");
        number("scale.stable-tolerance-jin", Group.ADAPTER, Type.DECIMAL, "稳定容差（斤）", "“稳定判断”为 APP 判断时，记得太早或太晚",
                "0", "10", "0.1");
        number("weigh.empty-below-jin", Group.ADAPTER, Type.DECIMAL, "空秤阈值（斤）", "“秤面已清空”判断不准", "0", "100", "10");
        number("weigh.merge-tolerance-pct", Group.ADAPTER, Type.INT, "合并称重识别范围（%）",
                "合并称重时“识别不准”的提示太多或太少", "1", "100", "10");
        number("weigh.merge-max-k", Group.ADAPTER, Type.INT, "合并称重最多箱数", "合并称重时“识别不准”的提示太多或太少",
                "1", "50", "8");
        enumOf("print.render", Group.ADAPTER, "标签生成方式", "标签上文字乱码、缺字", "text",
                c("text", "文字指令"), c("bitmap", "整张图片"));
        number("print.dpi", Group.ADAPTER, Type.INT, "打印分辨率（dpi）", "标签内容整体偏大偏小", "100", "600", "203");
        number("print.offset-x-mm", Group.ADAPTER, Type.DECIMAL, "水平偏移（mm）", "标签内容整体偏位", "-20", "20", "0");
        number("print.offset-y-mm", Group.ADAPTER, Type.DECIMAL, "垂直偏移（mm）", "标签内容整体偏位", "-20", "20", "0");
        enumOf("push.remark", Group.ADAPTER, "回传备注", "好生意不接受长备注或备注字段", "full",
                c("full", "逐箱 + 归类"), c("summary", "只带共几箱与归类"), c("off", "不带备注"));
        number("push.remark-max-length", Group.ADAPTER, Type.INT, "备注长度上限",
                "实测出备注长度上限（T-02）后填入，0 为不限；超过时自动降为只带汇总", "0", "100000", "0");

        // ---- 第二组：作业策略 ----
        enumOf("weigh.record-by", Group.POLICY, "记录方式", "自动记录在现场误记或不记", "auto",
                c("auto", "稳定后自动记录"), c("button", "按“记录”才记"));
        enumOf("weigh.merge-detect", Group.POLICY, "箱数识别", "箱数识别在现场经常出错", "auto",
                c("auto", "按重量识别箱数"), c("off", "每次按 1 箱记，箱数在最新一行改"));
        enumOf("weigh.manual-entry", Group.POLICY, "手工录入净重", "业务方不允许手工录入净重（关掉后秤不可用时无法收蛋）", "on", ON_OFF);
        enumOf("print.enabled", Group.POLICY, "打印标签", "打印机暂时用不了，不想让界面一直提示未打印", "on", ON_OFF);
        enumOf("submit.without-price", Group.POLICY, "缺规则或基准价时提交", "现场经常因为等规则或基准价而压单", "block",
                c("block", "不能提交"), c("allow", "可以先提交，审核前补齐价格"));
        enumOf("push.on-approve", Group.POLICY, "审核后回传", "好生意接口还没调通，但要先把审核做起来", "auto",
                c("auto", "审核即回传"), c("manual", "审核只锁定，回传另点"));
    }

    private static final Map<String, Def> BY_KEY = new LinkedHashMap<>();

    static {
        for (Def d : ALL) {
            if (BY_KEY.put(d.key(), d) != null) {
                throw new IllegalStateException("选项重复登记：" + d.key());
            }
        }
    }

    private Options() {
    }

    public static List<Def> of(Group... groups) {
        List<Group> wanted = List.of(groups);
        return ALL.stream().filter(d -> wanted.contains(d.group())).toList();
    }

    /** @return 没有登记过时为 null */
    public static Def find(String key) {
        return BY_KEY.get(key);
    }

    private static Choice c(String value, String label) {
        return new Choice(value, label);
    }

    private static void enumOf(String key, Group group, String name, String hint, String def, Choice... choices) {
        ALL.add(new Def(key, group, Type.ENUM, name, hint, List.of(choices), null, null, def));
    }

    private static void number(String key, Group group, Type type, String name, String hint, String min, String max,
            String def) {
        ALL.add(new Def(key, group, type, name, hint, List.of(), min, max, def));
    }
}

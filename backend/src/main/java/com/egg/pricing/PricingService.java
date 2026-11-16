package com.egg.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.basics.BasePrice;
import com.egg.basics.BasePriceMapper;
import com.egg.basics.OptionService;
import com.egg.basics.Spec;
import com.egg.basics.SpecService;
import com.egg.common.ApiException;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 全系统唯一算钱的地方（开发规格 3.2、v7 2.2）。APP 与 Web 只展示这里的结果。
 * <p>
 * 缺规则或缺基准价不报错：照常返回箱数、总净重、均重，{@code issues} 里列出 {@link #NO_RULE} / {@link #NO_BASE_PRICE}，
 * 单价与金额为 null。待审核单每次读取都调一次（实时算）；审核时由 P2-4 把结果冻结到单据上，这里不存任何东西。
 * <p>
 * 数值口径：均重取整对精确商做，不先四舍五入再取整（避免 46.4999 这类中间值被误进位）；价差、收购价格、金额
 * 各自四舍五入到分，金额用已到分的收购价格乘箱数。四舍五入统一为“恰好一半时远离零”（{@link RoundingMode#HALF_UP}），
 * 与原型 {@code priceWith} 的 {@code Math.round} 只在负数且恰好差半分时不同，见附录 E。
 */
@Service
public class PricingService {

    public static final String NO_RULE = "NO_RULE";
    public static final String NO_BASE_PRICE = "NO_BASE_PRICE";

    /**
     * @param boxNetKg    各箱净重（kg），可以为空列表
     * @param roundMode   为 null 取系统设置 {@code round.mode}；审核后的单据传冻结的值
     * @param trialRuleId 试算：换成这条规则（取它的系数），为 null 用农户当前绑定的规则
     * @param trialCoef   试算：把换算系数换成这个值，为 null 用规则里的；与 trialRuleId 可同时给，系数以它为准。
     *                    只替换系数，规则本身仍要存在，农户没有规则又没指定 trialRuleId 时照样是 NO_RULE
     */
    public record Request(long vendorId, long productId, long specId, LocalDate date, List<BigDecimal> boxNetKg,
            RoundMode roundMode, Long trialRuleId, BigDecimal trialCoef) {

        public Request(long vendorId, long productId, long specId, LocalDate date, List<BigDecimal> boxNetKg) {
            this(vendorId, productId, specId, date, boxNetKg, null, null, null);
        }
    }

    /**
     * 计价结果。重量 kg、金额元。没有箱子时均重及其后各项为 null；缺规则或缺基准价时单价与金额为 null，
     * 缺规则时价差也为 null，尾差照常给出。
     *
     * @param rawAvgKg  取整前的均重，只为展示（“46.05 四舍五入为 46”），保留 4 位小数
     * @param tailKg    尾差 X = 均重 − 标准净含量
     * @param priceDiff 价差 Y = 换算系数 × 尾差，到分
     */
    public record Result(int boxCount, BigDecimal totalKg, BigDecimal rawAvgKg, BigDecimal avgKg,
            BigDecimal standardNetKg, BigDecimal basePrice, Long ruleId, String ruleName, BigDecimal coefficient,
            BigDecimal tailKg, BigDecimal priceDiff, BigDecimal unitPrice, BigDecimal amount, RoundMode roundMode,
            List<String> issues) {
    }

    private final SpecService specs;
    private final BasePriceMapper basePrices;
    private final OptionService options;
    private final NamedParameterJdbcTemplate jdbc;

    public PricingService(SpecService specs, BasePriceMapper basePrices, OptionService options,
            NamedParameterJdbcTemplate jdbc) {
        this.specs = specs;
        this.basePrices = basePrices;
        this.options = options;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 取规则、取基准价，再算

    public Result price(Request req) {
        Spec spec = specs.get(req.specId());
        RoundMode mode = req.roundMode() != null ? req.roundMode() : RoundMode.parse(options.get("round.mode"));
        RuleRow rule = req.trialRuleId() != null ? ruleById(req.trialRuleId())
                : boundRule(req.vendorId(), req.productId(), req.specId());
        BasePrice bp = basePrices.selectOne(
                new QueryWrapper<BasePrice>().eq("price_date", req.date()).eq("spec_id", req.specId()));
        BigDecimal coef = rule == null ? null : req.trialCoef() != null ? req.trialCoef() : rule.coef();
        return compute(req.boxNetKg(), spec.stdNetKg, bp == null ? null : bp.basePrice, rule == null ? null : rule.id(),
                rule == null ? null : rule.name(), coef, mode);
    }

    private record RuleRow(long id, String name, BigDecimal coef) {
    }

    /** 该农户绑定的、启用的、商品与规格都相同的收购规则；保存时已保证至多一条，万一有多条取 id 最小的。 */
    private RuleRow boundRule(long vendorId, long productId, long specId) {
        List<RuleRow> rows = jdbc.query("SELECT r.id, r.name, r.coef FROM pricing_rule r "
                + "JOIN pricing_rule_bind b ON b.rule_id = r.id "
                + "WHERE b.target_id = :v AND r.kind = 'BUY' AND r.enabled = 1 AND r.product_id = :p AND r.spec_id = :s "
                + "ORDER BY r.id LIMIT 1",
                new MapSqlParameterSource().addValue("v", vendorId).addValue("p", productId).addValue("s", specId),
                (rs, i) -> new RuleRow(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("coef")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RuleRow ruleById(long id) {
        List<RuleRow> rows = jdbc.query("SELECT id, name, coef FROM pricing_rule WHERE id = :id AND kind = 'BUY'",
                new MapSqlParameterSource("id", id),
                (rs, i) -> new RuleRow(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("coef")));
        if (rows.isEmpty()) {
            throw ApiException.invalid("规则不存在");
        }
        return rows.get(0);
    }

    // ---------------------------------------------------------------- 纯计算

    /**
     * 审核后的单据拿冻结的各项来还原明细（尾差、价差、原始均重）时也走这里，计价仍只有这一处。
     *
     * @param basePrice null 表示当日没录基准价
     * @param coef      null 表示没有规则
     */
    public static Result compute(List<BigDecimal> boxNetKg, BigDecimal standardNetKg, BigDecimal basePrice, Long ruleId,
            String ruleName, BigDecimal coef, RoundMode mode) {
        List<String> issues = new ArrayList<>();
        if (coef == null) {
            issues.add(NO_RULE);
        }
        if (basePrice == null) {
            issues.add(NO_BASE_PRICE);
        }
        int n = boxNetKg.size();
        BigDecimal total = boxNetKg.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (n == 0) {
            return new Result(0, total, null, null, standardNetKg, basePrice, ruleId, ruleName, coef, null, null, null,
                    null, mode, issues);
        }
        BigDecimal count = BigDecimal.valueOf(n);
        BigDecimal rawAvg = total.divide(count, 4, RoundingMode.HALF_UP);
        BigDecimal avg = total.divide(count, 0, mode.mode);
        BigDecimal tail = avg.subtract(standardNetKg);
        BigDecimal diff = coef == null ? null : coef.multiply(tail).setScale(2, RoundingMode.HALF_UP);
        BigDecimal unit = null;
        BigDecimal amount = null;
        if (diff != null && basePrice != null) {
            unit = basePrice.add(diff).setScale(2, RoundingMode.HALF_UP);
            amount = unit.multiply(count).setScale(2, RoundingMode.HALF_UP);
        }
        return new Result(n, total, rawAvg, avg, standardNetKg, basePrice, ruleId, ruleName, coef, tail, diff, unit,
                amount, mode, issues);
    }
}

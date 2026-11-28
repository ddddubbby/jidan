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

import org.springframework.stereotype.Service;

/**
 * 全系统唯一算钱的地方（开发规格 3.2、v7 2.2）。APP 与 Web 只展示这里的结果。重量单位斤。
 * <p>
 * 规则由建单时选定（单据表头的 rule_id），不再按农户查绑定（附录 E R-1 行）。缺规则或缺基准价不报错：照常返回箱数、
 * 总净重、均重，{@code issues} 里列出 {@link #NO_RULE} / {@link #NO_BASE_PRICE}，单价与金额为 null。待审核单每次读取都调一次
 * （实时算）；审核时把结果冻结到单据上，这里不存任何东西。
 * <p>
 * 数值口径：每箱损耗调整加到每箱净重上，即均重 = 各箱净重之和 ÷ 箱数 + 每箱损耗调整，再按取整规则取到整数斤；取整对精确商做，
 * 不先四舍五入再取整（避免 92.4999 这类中间值被误进位）。价差、收购价格、金额各自四舍五入到分，金额用已到分的收购价格乘箱数。
 * 四舍五入统一为“恰好一半时远离零”（{@link RoundingMode#HALF_UP}），与原型 {@code priceWith} 的 {@code Math.round}
 * 只在负数且恰好差半分时不同，见附录 E。
 */
@Service
public class PricingService {

    public static final String NO_RULE = "NO_RULE";
    public static final String NO_BASE_PRICE = "NO_BASE_PRICE";

    /**
     * @param ruleId      单据选定的规则；null 表示没选（只有改版前的旧单会这样），结果里是 NO_RULE
     * @param specId      规格，取标准净含量与当日基准价；单据上存的是建单时规则的规格
     * @param boxNetJin   各箱净重（斤），可以为空列表
     * @param roundMode   为 null 取系统设置 {@code round.mode}；审核后的单据传冻结的值
     * @param trialCoef   试算：把换算系数换成这个值，为 null 用规则里的
     * @param trialLossJin 试算：把每箱损耗调整换成这个值，为 null 用规则里的
     */
    public record Request(Long ruleId, long specId, LocalDate date, List<BigDecimal> boxNetJin, RoundMode roundMode,
            BigDecimal trialCoef, BigDecimal trialLossJin) {

        public Request(Long ruleId, long specId, LocalDate date, List<BigDecimal> boxNetJin) {
            this(ruleId, specId, date, boxNetJin, null, null, null);
        }
    }

    /**
     * 计价结果。重量斤、金额元。没有箱子时均重及其后各项为 null；缺规则或缺基准价时单价与金额为 null，
     * 缺规则时价差也为 null，尾差照常给出。
     *
     * @param rawAvgJin 称得的均重（各箱净重之和 ÷ 箱数，未加损耗、未取整），只为展示，保留 4 位小数
     * @param lossJin   每箱损耗调整；没有规则时为 0
     * @param avgJin    均重 W = 称得的均重 + 每箱损耗调整，按取整规则取整
     * @param tailJin   尾差 X = 均重 − 标准净含量
     * @param priceDiff 价差 Y = 换算系数 × 尾差，到分
     */
    public record Result(int boxCount, BigDecimal totalJin, BigDecimal rawAvgJin, BigDecimal lossJin, BigDecimal avgJin,
            BigDecimal standardNetJin, BigDecimal basePrice, Long ruleId, String ruleName, BigDecimal coefficient,
            BigDecimal tailJin, BigDecimal priceDiff, BigDecimal unitPrice, BigDecimal amount, RoundMode roundMode,
            List<String> issues) {
    }

    private final SpecService specs;
    private final BasePriceMapper basePrices;
    private final OptionService options;
    private final PricingRuleMapper rules;

    public PricingService(SpecService specs, BasePriceMapper basePrices, OptionService options, PricingRuleMapper rules) {
        this.specs = specs;
        this.basePrices = basePrices;
        this.options = options;
        this.rules = rules;
    }

    // ---------------------------------------------------------------- 取规则、取基准价，再算

    public Result price(Request req) {
        Spec spec = specs.get(req.specId());
        RoundMode mode = req.roundMode() != null ? req.roundMode() : RoundMode.parse(options.get("round.mode"));
        PricingRule rule = req.ruleId() == null ? null : rules.selectById(req.ruleId());
        if (req.ruleId() != null && rule == null) {
            throw ApiException.invalid("规则不存在");
        }
        BasePrice bp = basePrices.selectOne(
                new QueryWrapper<BasePrice>().eq("price_date", req.date()).eq("spec_id", req.specId()));
        BigDecimal coef = rule == null ? null : req.trialCoef() != null ? req.trialCoef() : rule.coef;
        BigDecimal loss = rule == null ? null : req.trialLossJin() != null ? req.trialLossJin() : rule.lossJin;
        return compute(req.boxNetJin(), spec.stdNetJin, bp == null ? null : bp.basePrice, rule == null ? null : rule.id,
                rule == null ? null : rule.name, coef, loss, mode);
    }

    // ---------------------------------------------------------------- 纯计算

    /**
     * 审核后的单据拿冻结的各项来还原明细（尾差、价差、原始均重）时也走这里，计价仍只有这一处。
     *
     * @param basePrice null 表示当日没录基准价
     * @param coef      null 表示没有规则
     * @param lossJin   每箱损耗调整，null 按 0
     */
    public static Result compute(List<BigDecimal> boxNetJin, BigDecimal standardNetJin, BigDecimal basePrice, Long ruleId,
            String ruleName, BigDecimal coef, BigDecimal lossJin, RoundMode mode) {
        List<String> issues = new ArrayList<>();
        if (coef == null) {
            issues.add(NO_RULE);
        }
        if (basePrice == null) {
            issues.add(NO_BASE_PRICE);
        }
        BigDecimal loss = lossJin == null ? BigDecimal.ZERO : lossJin;
        int n = boxNetJin.size();
        BigDecimal total = boxNetJin.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (n == 0) {
            return new Result(0, total, null, loss, null, standardNetJin, basePrice, ruleId, ruleName, coef, null, null,
                    null, null, mode, issues);
        }
        BigDecimal count = BigDecimal.valueOf(n);
        BigDecimal rawAvg = total.divide(count, 4, RoundingMode.HALF_UP);
        // 加损耗后再整体除，取整对精确商做：(总净重 + 损耗 × 箱数) ÷ 箱数
        BigDecimal avg = total.add(loss.multiply(count)).divide(count, 0, mode.mode);
        BigDecimal tail = avg.subtract(standardNetJin);
        BigDecimal diff = coef == null ? null : coef.multiply(tail).setScale(2, RoundingMode.HALF_UP);
        BigDecimal unit = null;
        BigDecimal amount = null;
        if (diff != null && basePrice != null) {
            unit = basePrice.add(diff).setScale(2, RoundingMode.HALF_UP);
            amount = unit.multiply(count).setScale(2, RoundingMode.HALF_UP);
        }
        return new Result(n, total, rawAvg, loss, avg, standardNetJin, basePrice, ruleId, ruleName, coef, tail, diff,
                unit, amount, mode, issues);
    }
}

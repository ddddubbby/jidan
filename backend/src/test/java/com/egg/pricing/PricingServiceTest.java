package com.egg.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.egg.MySqlTestConfig;
import com.egg.basics.OptionService;
import com.egg.basics.Options.Group;
import com.egg.common.ApiException;
import com.egg.pricing.PricingService.Request;
import com.egg.pricing.PricingService.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * P2-1 验收：逐条跑通 fixtures/pricing.json；缺规则、缺基准价各返回对应的 issues。
 * 算例文件的字段名见开发规格附录 E（R2-4）：重量 斤、金额元，按数值比较。
 * 前半是不碰数据库的纯计算（算例文件逐条、v7 2.2 精度表），后半在真 MySQL 上验证取规则、取基准价、取整规则选项与试算参数。
 */
@SpringBootTest(properties = {"egg.admin.phone=13700000001", "egg.admin.password=boss-pass-1", "egg.admin.name=计价测试管理员"})
@Import(MySqlTestConfig.class)
class PricingServiceTest {

    private static final JsonNode FIXTURE = load();

    private static JsonNode load() {
        try {
            return new ObjectMapper().readTree(Path.of("..", "fixtures", "pricing.json").toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asText());
    }

    private static List<BigDecimal> boxes(JsonNode arr) {
        List<BigDecimal> out = new ArrayList<>();
        arr.forEach(n -> out.add(num(n)));
        return out;
    }

    private static List<BigDecimal> boxes(double... jins) {
        List<BigDecimal> out = new ArrayList<>();
        for (double k : jins) {
            out.add(BigDecimal.valueOf(k));
        }
        return out;
    }

    private static void assertNum(BigDecimal actual, JsonNode expected, String what) {
        assertThat(actual).as(what).isNotNull();
        assertThat(actual.compareTo(num(expected))).as("%s：实际 %s，期望 %s", what, actual, expected).isZero();
    }

    // ---------------------------------------------------------------- 纯计算：算例文件

    @Test
    void everyFixtureCaseMatches() {
        JsonNode cases = FIXTURE.get("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(7);
        for (JsonNode c : cases) {
            String id = c.get("id").asText();
            JsonNode in = c.get("input");
            JsonNode ex = c.get("expected");
            List<BigDecimal> jins = boxes(in.get("boxNetJin"));
            Result r = PricingService.compute(jins, num(in.get("standardNetJin")), num(in.get("basePrice")), 1L, "规则",
                    num(in.get("coefficient")), in.has("lossJin") ? num(in.get("lossJin")) : null,
                    RoundMode.parse(in.get("rounding").asText()));
            assertThat(r.boxCount()).as(id + " boxCount").isEqualTo(jins.size());
            assertNum(r.totalJin(), ex.get("totalJin"), id + " totalJin");
            assertNum(r.rawAvgJin(), ex.get("rawAvgJin"), id + " rawAvgJin");
            assertNum(r.avgJin(), ex.get("avgJin"), id + " avgJin");
            assertNum(r.tailJin(), ex.get("tailJin"), id + " tailJin");
            assertNum(r.priceDiff(), ex.get("priceDiff"), id + " priceDiff");
            assertNum(r.unitPrice(), ex.get("unitPrice"), id + " unitPrice");
            assertNum(r.amount(), ex.get("amount"), id + " amount");
            assertThat(r.issues()).as(id + " issues").isEmpty();
        }
    }

    /** v7 2.2 精度表：同一批 10 箱，三种规则下的取整均重。 */
    @Test
    void roundingTableMatches() {
        for (JsonNode row : FIXTURE.get("roundingTable")) {
            int n = row.get("boxCount").asInt();
            // 每箱取总重的平均值（总重除以箱数，各箱同重），只看取整
            BigDecimal total = num(row.get("totalJin"));
            List<BigDecimal> jins = new ArrayList<>();
            BigDecimal each = total.divide(BigDecimal.valueOf(n));
            for (int i = 0; i < n; i++) {
                jins.add(each);
            }
            for (RoundMode m : RoundMode.values()) {
                Result r = PricingService.compute(jins, BigDecimal.valueOf(45), BigDecimal.valueOf(180), 1L, "规则",
                        BigDecimal.ONE, null, m);
                assertNum(r.avgJin(), row.get(m.name().toLowerCase()), "总重 " + total + " " + m);
            }
        }
    }

    /** 恰好一半不能被中间的四舍五入带偏：46.49999 取整为 46，而不是先到 46.5 再进位。 */
    @Test
    void roundsTheExactQuotient() {
        Result r = PricingService.compute(boxes(46.49999, 46.49999), BigDecimal.valueOf(45), BigDecimal.valueOf(180), 1L,
                "规则", BigDecimal.ONE, null, RoundMode.HALF);
        assertThat(r.avgJin()).isEqualByComparingTo("46");
        // 4 位小数的展示值是 46.5000，但取整看的是精确商
        assertThat(r.rawAvgJin()).isEqualByComparingTo("46.5");
    }

    @Test
    void roundModeParsesBothCases() {
        assertThat(RoundMode.parse("half")).isEqualTo(RoundMode.HALF);
        assertThat(RoundMode.parse("CEIL")).isEqualTo(RoundMode.CEIL);
        assertThat(RoundMode.parse("floor")).isEqualTo(RoundMode.FLOOR);
    }

    // ---------------------------------------------------------------- 真库：取规则、取基准价、issues

    @Autowired
    PricingService pricing;
    @Autowired
    OptionService options;
    @Autowired
    JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    private long pink, s360, s480, rule1, rule2;

    @BeforeEach
    void fixtures() {
        for (String t : List.of("base_price", "pricing_rule", "vendor_phone", "vendor", "customer",
                "product", "spec", "`option`")) {
            jdbc.update("DELETE FROM " + t);
        }
        pink = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0001', '粉蛋', '箱')");
        s360 = insert("INSERT INTO spec (name, egg_count, std_net_jin, enabled) VALUES ('360枚/箱', 360, 45, 1)");
        s480 = insert("INSERT INTO spec (name, egg_count, std_net_jin, enabled) VALUES ('480枚/箱', 480, 60, 1)");
        rule1 = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, coef, enabled) "
                + "VALUES ('粉蛋360标准', 'BUY', " + pink + ", " + s360 + ", 1, 1)");
        rule2 = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, loss_jin, coef, enabled) "
                + "VALUES ('粉蛋360优价', 'BUY', " + pink + ", " + s360 + ", 0, 1.5, 1)");
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 180)", TODAY, s360);
    }

    private long insert(String sql) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }

    private static final List<BigDecimal> LISI_BOXES = boxes(46.5, 45.8, 46, 46, 46, 45.6, 45.6, 45.6, 45.6, 47.8);

    private Result quote(Long rule, long spec, LocalDate date) {
        return pricing.price(new Request(rule, spec, date, LISI_BOXES));
    }

    @Test
    void baselineLiSiGets1810() {
        Result r = quote(rule1, s360, TODAY);
        assertThat(r.issues()).isEmpty();
        assertThat(r.boxCount()).isEqualTo(10);
        assertThat(r.totalJin()).isEqualByComparingTo("460.5");
        assertThat(r.avgJin()).isEqualByComparingTo("46");
        assertThat(r.lossJin()).isEqualByComparingTo("0");
        assertThat(r.standardNetJin()).isEqualByComparingTo("45");
        assertThat(r.basePrice()).isEqualByComparingTo("180");
        assertThat(r.ruleId()).isEqualTo(rule1);
        assertThat(r.ruleName()).isEqualTo("粉蛋360标准");
        assertThat(r.coefficient()).isEqualByComparingTo("1");
        assertThat(r.unitPrice()).isEqualByComparingTo("181");
        assertThat(r.amount()).isEqualByComparingTo("1810");
        assertThat(r.roundMode()).isEqualTo(RoundMode.HALF);
    }

    /** 只有改版前没对上规则的旧单会这样：规则为空，照常给重量，不给价。 */
    @Test
    void noRuleReportsIssueButKeepsWeights() {
        Result r = quote(null, s360, TODAY);
        assertThat(r.issues()).containsExactly(PricingService.NO_RULE);
        assertThat(r.boxCount()).isEqualTo(10);
        assertThat(r.totalJin()).isEqualByComparingTo("460.5");
        assertThat(r.avgJin()).isEqualByComparingTo("46");
        assertThat(r.basePrice()).isEqualByComparingTo("180");
        assertThat(r.ruleId()).isNull();
        assertThat(r.unitPrice()).isNull();
        assertThat(r.amount()).isNull();
    }

    @Test
    void noBasePriceReportsIssueButKeepsWeights() {
        // 当天没录价，规则在
        Result r = quote(rule1, s360, TODAY.plusDays(1));
        assertThat(r.issues()).containsExactly(PricingService.NO_BASE_PRICE);
        assertThat(r.totalJin()).isEqualByComparingTo("460.5");
        assertThat(r.avgJin()).isEqualByComparingTo("46");
        assertThat(r.basePrice()).isNull();
        assertThat(r.ruleId()).isEqualTo(rule1);
        assertThat(r.unitPrice()).isNull();
        assertThat(r.amount()).isNull();
    }

    /** 选 480 枚/箱且没选规则：基准价也只录了 360，两样都缺。 */
    @Test
    void bothMissingListsBoth() {
        Result r = quote(null, s480, TODAY);
        assertThat(r.issues()).containsExactly(PricingService.NO_RULE, PricingService.NO_BASE_PRICE);
        assertThat(r.avgJin()).isEqualByComparingTo("46");
        assertThat(r.tailJin()).isEqualByComparingTo("-14");
        assertThat(r.priceDiff()).isNull();
        assertThat(r.unitPrice()).isNull();
        assertThat(r.amount()).isNull();
    }

    /** 已经选了的规则后来停用：这张单照常按它算，停用只影响新建单时能不能选。 */
    @Test
    void disabledRuleStillPricesExistingOrders() {
        jdbc.update("UPDATE pricing_rule SET enabled = 0 WHERE id = ?", rule1);
        assertThat(quote(rule1, s360, TODAY).amount()).isEqualByComparingTo("1810");
    }

    /** 每箱损耗调整加到每箱净重上再取整：460.5 ÷ 10 = 46.05，−1 得 45.05 取整 45，尾差 0。 */
    @Test
    void lossAdjustsAverageBeforeRounding() {
        jdbc.update("UPDATE pricing_rule SET loss_jin = -1 WHERE id = ?", rule1);
        Result r = quote(rule1, s360, TODAY);
        assertThat(r.rawAvgJin()).isEqualByComparingTo("46.05");
        assertThat(r.lossJin()).isEqualByComparingTo("-1");
        assertThat(r.avgJin()).isEqualByComparingTo("45");
        assertThat(r.unitPrice()).isEqualByComparingTo("180");
        assertThat(r.amount()).isEqualByComparingTo("1800");

        jdbc.update("UPDATE pricing_rule SET loss_jin = 0.5 WHERE id = ?", rule1);
        Result plus = quote(rule1, s360, TODAY);
        assertThat(plus.avgJin()).isEqualByComparingTo("47");
        assertThat(plus.amount()).isEqualByComparingTo("1820");
    }

    @Test
    void noBoxesGivesZeroTotalsAndNoPrice() {
        Result r = pricing.price(new Request(rule1, s360, TODAY, List.of()));
        assertThat(r.boxCount()).isZero();
        assertThat(r.totalJin()).isEqualByComparingTo("0");
        assertThat(r.avgJin()).isNull();
        assertThat(r.unitPrice()).isNull();
        assertThat(r.amount()).isNull();
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void roundModeComesFromSettingsAndExplicitValueWins() {
        // 同一批 10 箱 460.5，进位后均重 47、单价 182、金额 1820
        options.update(Map.of("round.mode", "ceil"), null, Group.SETTING);
        Result r = quote(rule1, s360, TODAY);
        assertThat(r.roundMode()).isEqualTo(RoundMode.CEIL);
        assertThat(r.avgJin()).isEqualByComparingTo("47");
        assertThat(r.amount()).isEqualByComparingTo("1820");

        // 冻结在单据上的是大写取值，显式传入时不看系统设置
        Result frozen = pricing.price(new Request(rule1, s360, TODAY, LISI_BOXES, RoundMode.parse("HALF"), null, null));
        assertThat(frozen.roundMode()).isEqualTo(RoundMode.HALF);
        assertThat(frozen.amount()).isEqualByComparingTo("1810");
    }

    @Test
    void basePriceFollowsTheDateAndSpec() {
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 200)", TODAY.plusDays(1), s360);
        assertThat(quote(rule1, s360, TODAY).unitPrice()).isEqualByComparingTo("181");
        assertThat(quote(rule1, s360, TODAY.plusDays(1)).unitPrice()).isEqualByComparingTo("201");
    }

    @Test
    void trialCoefficientAndLoss() {
        // 换一条规则就是换 ruleId：系数 1.5，价差 1.5，单价 181.5，金额 1815
        Result swapped = quote(rule2, s360, TODAY);
        assertThat(swapped.ruleId()).isEqualTo(rule2);
        assertThat(swapped.coefficient()).isEqualByComparingTo("1.5");
        assertThat(swapped.unitPrice()).isEqualByComparingTo("181.5");
        assertThat(swapped.amount()).isEqualByComparingTo("1815");

        // 换一个系数：规则不变，只是系数换成 2
        Result coef = pricing.price(new Request(rule1, s360, TODAY, LISI_BOXES, null, new BigDecimal("2"), null));
        assertThat(coef.ruleId()).isEqualTo(rule1);
        assertThat(coef.unitPrice()).isEqualByComparingTo("182");
        assertThat(coef.amount()).isEqualByComparingTo("1820");

        // 换一个损耗：+1，均重 47.05 取整 47
        Result loss = pricing.price(new Request(rule1, s360, TODAY, LISI_BOXES, null, null, BigDecimal.ONE));
        assertThat(loss.lossJin()).isEqualByComparingTo("1");
        assertThat(loss.amount()).isEqualByComparingTo("1820");

        // 试算不写库
        assertThat(jdbc.queryForObject("SELECT coef FROM pricing_rule WHERE id = ?", BigDecimal.class, rule1))
                .isEqualByComparingTo("1");
        assertThat(quote(rule1, s360, TODAY).amount()).isEqualByComparingTo("1810");
    }

    @Test
    void unknownRuleOrSpecIsRejected() {
        assertThatThrownBy(() -> quote(999_999L, s360, TODAY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> pricing.price(new Request(rule1, 999_999L, TODAY, LISI_BOXES)))
                .isInstanceOf(ApiException.class);
    }
}

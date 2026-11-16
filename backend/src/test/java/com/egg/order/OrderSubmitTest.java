package com.egg.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.egg.MySqlTestConfig;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * P2-2 验收：下发内容含当日基准价、业务设置（打印时机、标签内容、两个设备型号）与 APP 端选项；
 * 批次号当日从 1 递增；同一批次号提交两次只有一张单（APP、WEB 两种来源各测一次）。
 * 另覆盖计价预览（含试算）、缺价时的提交开关、Web 自建单不标手录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.admin.phone=13700000011", "egg.admin.password=boss-pass-2", "egg.admin.name=单据测试管理员"})
@Import(MySqlTestConfig.class)
class OrderSubmitTest {

    /** v7 6.1、6.4：李四 10 箱粉蛋，合计 460.5kg，均重 46，181 元/箱，1810 元 */
    private static final List<Double> LI_SI_BOXES = List.of(46.5, 45.8, 46.0, 46.0, 46.0, 45.6, 45.6, 45.6, 45.6, 47.8);

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;

    private static volatile String adminToken;
    private static volatile String scaleToken;

    private long pink, red, s360, s480, liSi, wangXiuYing, warehouse;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void fixtures() {
        for (String t : List.of("weigh_record", "purchase_order", "batch_seq", "pricing_rule_bind", "pricing_rule",
                "base_price", "vendor_phone", "vendor", "warehouse", "product", "spec", "tare_box", "`option`")) {
            jdbc.update("DELETE FROM " + t);
        }
        pink = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0001', '粉蛋', '箱')");
        red = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0002', '红蛋', '箱')");
        s360 = insert("INSERT INTO spec (name, egg_count, std_net_kg, enabled) VALUES ('360枚/箱', 360, 45, 1)");
        s480 = insert("INSERT INTO spec (name, egg_count, std_net_kg, enabled) VALUES ('480枚/箱', 480, 60, 1)");
        liSi = insert("INSERT INTO vendor (hs_code, name) VALUES ('GYS0001', '李四')");
        wangXiuYing = insert("INSERT INTO vendor (hs_code, name) VALUES ('GYS0002', '王秀英')");
        warehouse = insert("INSERT INTO warehouse (hs_code, name) VALUES ('CK0001', '一号库')");
        jdbc.update("INSERT INTO tare_box (name, tare_kg, hotkey, enabled) VALUES ('2号包装箱', 2, 1, 1)");
        jdbc.update("INSERT INTO tare_box (name, tare_kg, hotkey, enabled) VALUES ('不常用箱', 3, 0, 1)");
        // 360 当日有基准价、480 没有
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 180)", today, s360);
        long rule = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, coef, enabled)"
                + " VALUES ('粉蛋李四收购定价方案', 'BUY', " + pink + ", " + s360 + ", 1, 1)");
        jdbc.update("INSERT INTO pricing_rule_bind (rule_id, target_id) VALUES (?, ?)", rule, liSi);
    }

    private long insert(String sql) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }

    private String token(String phone, String password, String client) {
        return http.postForObject("/api/auth/login", Map.of("phone", phone, "password", password, "client", client),
                JsonNode.class).get("token").asText();
    }

    private String admin() {
        if (adminToken == null) {
            adminToken = token("13700000011", "boss-pass-2", "WEB");
        }
        return adminToken;
    }

    private String scale() {
        if (scaleToken == null) {
            JsonNode created = call(HttpMethod.POST, "/api/accounts", Map.of("name", "测试磅秤员", "phone", "13700000012"),
                    admin()).getBody();
            scaleToken = token("13700000012", created.get("password").asText(), "APP");
        }
        return scaleToken;
    }

    private ResponseEntity<JsonNode> call(HttpMethod m, String url, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return http.exchange(url, m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private Map<String, Object> box(int seq, double net) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("seq", seq);
        m.put("netKg", net);
        m.put("grossKg", net + 2);
        m.put("tareKg", 2);
        m.put("tareName", "2号包装箱");
        m.put("groupNo", seq);
        m.put("groupK", 1);
        m.put("manual", false);
        m.put("printed", true);
        return m;
    }

    private List<Map<String, Object>> boxes(List<Double> nets) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < nets.size(); i++) {
            out.add(box(i + 1, nets.get(i)));
        }
        return out;
    }

    private Map<String, Object> submission(String batchNo, long vendor, long product, long spec, List<Double> nets) {
        return Map.of("batchNo", batchNo, "vendorId", vendor, "productId", product, "specId", spec,
                "warehouseId", warehouse, "boxes", boxes(nets));
    }

    private String newBatch(String token) {
        return call(HttpMethod.POST, "/api/batches", null, token).getBody().get("batchNo").asText();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---------------- bootstrap ----------------

    @Test
    void bootstrapDeliversPricesSettingsAndAppOptions() {
        JsonNode b = call(HttpMethod.GET, "/api/app/bootstrap", null, scale()).getBody();

        assertThat(b.get("date").asText()).isEqualTo(today.toString());
        assertThat(b.get("products")).hasSize(2);
        assertThat(b.get("products").get(0).get("name").asText()).isEqualTo("粉蛋");
        assertThat(b.get("warehouses").get(0).get("name").asText()).isEqualTo("一号库");
        // 只下发热键皮重
        assertThat(b.get("tares")).hasSize(1);
        assertThat(b.get("tares").get(0).get("tareKg").decimalValue()).isEqualByComparingTo("2");

        // 当日基准价与标准净含量；没录的规格为 null，APP 标“无基准价”
        JsonNode specs = b.get("specs");
        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).get("stdNetKg").decimalValue()).isEqualByComparingTo("45");
        assertThat(specs.get(0).get("basePrice").decimalValue()).isEqualByComparingTo("180");
        assertThat(specs.get(1).get("name").asText()).isEqualTo("480枚/箱");
        assertThat(specs.get(1).get("basePrice").isNull()).isTrue();

        // 业务设置：没存过时取默认值，C-1 的四项在内
        JsonNode settings = b.get("settings");
        assertThat(settings.get("round.mode").asText()).isEqualTo("half");
        assertThat(settings.get("print.timing").asText()).isEqualTo("per-box");
        assertThat(settings.get("label.content").asText()).isEqualTo("minimal");
        assertThat(settings.get("device.scale-model").asText()).isEqualTo("lanstar-ble");
        assertThat(settings.get("device.printer-model").asText()).isEqualTo("gainscha-gp-m32b");
        assertThat(settings.get("label.width-mm").asText()).isEqualTo("60");

        // APP 端选项：设备与作业两组，不含只有后端用的回传选项
        JsonNode options = b.get("options");
        assertThat(options.get("scale.tare-by").asText()).isEqualTo("scale");
        assertThat(options.get("weigh.merge-tolerance-pct").asText()).isEqualTo("10");
        assertThat(options.get("print.render").asText()).isEqualTo("text");
        assertThat(options.get("submit.without-price").asText()).isEqualTo("block");
        assertThat(options.has("push.remark")).isFalse();
        assertThat(options.has("push.on-approve")).isFalse();
    }

    @Test
    void bootstrapFollowsSettingChangesAtOnce() {
        jdbc.update("INSERT INTO `option` (opt_key, opt_value) VALUES ('print.timing', 'after-confirm')");
        jdbc.update("INSERT INTO `option` (opt_key, opt_value) VALUES ('weigh.record-by', 'button')");
        JsonNode b = call(HttpMethod.GET, "/api/app/bootstrap", null, scale()).getBody();
        assertThat(b.get("settings").get("print.timing").asText()).isEqualTo("after-confirm");
        assertThat(b.get("options").get("weigh.record-by").asText()).isEqualTo("button");
    }

    @Test
    void bootstrapIsOnlyForScaleOperators() {
        var res = call(HttpMethod.GET, "/api/app/bootstrap", null, admin());
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/app/bootstrap", null, null).getStatusCode().value()).isEqualTo(401);
    }

    // ---------------- 批次号 ----------------

    @Test
    void batchNumbersCountUpFromOneEachDay() {
        String day = today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(newBatch(scale())).isEqualTo("SG" + day + "-1");
        assertThat(newBatch(scale())).isEqualTo("SG" + day + "-2");
        assertThat(newBatch(admin())).isEqualTo("SG" + day + "-3");

        // Web 新建单带收购日期：那一天自己从 1 数，不影响当日
        var other = call(HttpMethod.POST, "/api/batches", Map.of("date", "2026-11-11"), admin()).getBody();
        assertThat(other.get("batchNo").asText()).isEqualTo("SG20261111-1");
        assertThat(other.get("date").asText()).isEqualTo("2026-11-11");
        assertThat(newBatch(scale())).isEqualTo("SG" + day + "-4");
        assertThat(count("SELECT last_seq FROM batch_seq WHERE biz_date = ?", today)).isEqualTo(4);
    }

    @Test
    void concurrentBatchRequestsNeverShareANumber() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                jobs.add(() -> newBatch(scale()));
            }
            List<String> got = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(jobs)) {
                got.add(f.get());
            }
            assertThat(got).doesNotHaveDuplicates().hasSize(24);
        } finally {
            pool.shutdown();
        }
        assertThat(count("SELECT last_seq FROM batch_seq WHERE biz_date = ?", today)).isEqualTo(24);
    }

    @Test
    void batchNoFormatAndDerivedCode() {
        assertThat(BatchNo.of(LocalDate.of(2026, 11, 15), 2)).isEqualTo("SG20261115-2");
        assertThat(BatchNo.codeOf("SG20261115-2")).isEqualTo("CGRK20261115002");
        assertThat(BatchNo.codeOf("SG20261115-12")).isEqualTo("CGRK20261115012");
        assertThat(BatchNo.dateOf("SG20261115-2")).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    // ---------------- 计价预览 ----------------

    @Test
    void quotePricesLiSiExampleWithoutWriting() {
        var res = call(HttpMethod.POST, "/api/orders/quote", Map.of("vendorId", liSi, "productId", pink, "specId", s360,
                "boxNetKg", LI_SI_BOXES), scale());
        JsonNode q = res.getBody();
        assertThat(q.get("boxCount").asInt()).isEqualTo(10);
        assertThat(q.get("totalKg").decimalValue()).isEqualByComparingTo("460.5");
        assertThat(q.get("avgKg").decimalValue()).isEqualByComparingTo("46");
        assertThat(q.get("unitPrice").decimalValue()).isEqualByComparingTo("181");
        assertThat(q.get("amount").decimalValue()).isEqualByComparingTo("1810");
        assertThat(q.get("issues")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM purchase_order")).isZero();
        assertThat(count("SELECT COUNT(*) FROM batch_seq")).isZero();
    }

    @Test
    void quoteTrialCoefficientChangesPriceOnly() {
        var q = call(HttpMethod.POST, "/api/orders/quote", Map.of("vendorId", liSi, "productId", pink, "specId", s360,
                "boxNetKg", LI_SI_BOXES, "trialCoef", 2), scale()).getBody();
        assertThat(q.get("unitPrice").decimalValue()).isEqualByComparingTo("182");
        assertThat(q.get("amount").decimalValue()).isEqualByComparingTo("1820");
        // 规则本身没变
        var again = call(HttpMethod.POST, "/api/orders/quote", Map.of("vendorId", liSi, "productId", pink, "specId", s360,
                "boxNetKg", LI_SI_BOXES), scale()).getBody();
        assertThat(again.get("amount").decimalValue()).isEqualByComparingTo("1810");
    }

    @Test
    void quoteReportsMissingRuleAndBasePrice() {
        var q = call(HttpMethod.POST, "/api/orders/quote", Map.of("vendorId", wangXiuYing, "productId", pink,
                "specId", s480, "boxNetKg", List.of(60.1, 59.9)), scale()).getBody();
        assertThat(q.get("boxCount").asInt()).isEqualTo(2);
        assertThat(q.get("issues")).extracting(JsonNode::asText).containsExactlyInAnyOrder("NO_RULE", "NO_BASE_PRICE");
        assertThat(q.get("unitPrice").isNull()).isTrue();
        assertThat(q.get("amount").isNull()).isTrue();
    }

    // ---------------- 提交 ----------------

    @Test
    void appSubmitTwiceWithSameBatchNoMakesOneOrder() {
        String batchNo = newBatch(scale());
        var body = submission(batchNo, liSi, pink, s360, LI_SI_BOXES);

        var first = call(HttpMethod.POST, "/api/orders", body, scale());
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        JsonNode a = first.getBody();
        assertThat(a.get("batchNo").asText()).isEqualTo(batchNo);
        assertThat(a.get("code").asText()).isEqualTo(BatchNo.codeOf(batchNo));
        assertThat(a.get("status").asText()).isEqualTo("PENDING");
        assertThat(a.get("source").asText()).isEqualTo("APP");
        assertThat(a.get("boxCount").asInt()).isEqualTo(10);
        assertThat(a.get("totalNetKg").decimalValue()).isEqualByComparingTo("460.5");
        assertThat(a.get("avgKg").decimalValue()).isEqualByComparingTo("46");
        assertThat(a.get("submittedPrice").decimalValue()).isEqualByComparingTo("181");
        assertThat(a.get("submittedAmount").decimalValue()).isEqualByComparingTo("1810");

        // 重试：网络没回来，APP 带着同一个批次号再交一次，当作成功，拿到原来那张单
        var second = call(HttpMethod.POST, "/api/orders", body, scale());
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("id").asLong()).isEqualTo(a.get("id").asLong());
        assertThat(second.getBody().get("submittedAmount").decimalValue()).isEqualByComparingTo("1810");

        assertThat(count("SELECT COUNT(*) FROM purchase_order")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM weigh_record")).isEqualTo(10);

        // 表头、流水原样存下，状态待审核，审核用的冻结字段为空
        var row = jdbc.queryForMap("SELECT * FROM purchase_order");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("source")).isEqualTo("APP");
        assertThat(row.get("biz_date").toString()).isEqualTo(today.toString());
        assertThat(row.get("vendor_id")).isEqualTo(liSi);
        assertThat(row.get("warehouse_id")).isEqualTo(warehouse);
        assertThat(row.get("amount")).isNull();
        assertThat(row.get("price")).isNull();
        var third = jdbc.queryForMap("SELECT * FROM weigh_record WHERE seq = 3");
        assertThat((BigDecimal) third.get("net_kg")).isEqualByComparingTo("46");
        assertThat((BigDecimal) third.get("gross_kg")).isEqualByComparingTo("48");
        assertThat(third.get("tare_name")).isEqualTo("2号包装箱");
        assertThat(third.get("printed")).isEqualTo(true);
    }

    @Test
    void appKeepsManualMarkAndRawReading() {
        String batchNo = newBatch(scale());
        var boxes = boxes(List.of(30.0, 29.2));
        boxes.get(1).put("manual", true);
        boxes.get(1).put("rawKg", 30.4);
        boxes.get(1).remove("grossKg");
        var res = call(HttpMethod.POST, "/api/orders", Map.of("batchNo", batchNo, "vendorId", liSi, "productId", pink,
                "specId", s360, "warehouseId", warehouse, "boxes", boxes), scale());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        var r = jdbc.queryForMap("SELECT * FROM weigh_record WHERE seq = 2");
        assertThat(r.get("manual")).isEqualTo(true);
        assertThat((BigDecimal) r.get("raw_kg")).isEqualByComparingTo("30.4");
        assertThat(r.get("gross_kg")).isNull();
    }

    @Test
    void webSubmitTwiceWithSameBatchNoMakesOneOrderAndIsNotMarkedManual() {
        // Web 自建单：收购日期由管理员选，批次号按该日期取
        var batch = call(HttpMethod.POST, "/api/batches", Map.of("date", today.minusDays(1).toString()), admin()).getBody();
        String batchNo = batch.get("batchNo").asText();
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 178)", today.minusDays(1), s360);
        // Web 只填净重；就算误带了手录标记与原始读数也不采用（v7 WEB-06）
        var webBoxes = new ArrayList<Map<String, Object>>();
        List<Double> nets = List.of(46.5, 45.5, 46.0, 46.0, 46.0, 46.0, 46.0, 46.0, 46.0, 46.5);
        for (int i = 0; i < nets.size(); i++) {
            webBoxes.add(new java.util.HashMap<>(Map.of("seq", i + 1, "netKg", nets.get(i), "manual", true, "rawKg", 1)));
        }
        var body = Map.of("batchNo", batchNo, "vendorId", liSi, "productId", pink, "specId", s360,
                "warehouseId", warehouse, "boxes", webBoxes);

        JsonNode a = call(HttpMethod.POST, "/api/orders", body, admin()).getBody();
        // 10 箱合计 460.5kg：数量 10、均重 46（v7 WEB-06 验收）
        assertThat(a.get("source").asText()).isEqualTo("WEB");
        assertThat(a.get("boxCount").asInt()).isEqualTo(10);
        assertThat(a.get("totalNetKg").decimalValue()).isEqualByComparingTo("460.5");
        assertThat(a.get("avgKg").decimalValue()).isEqualByComparingTo("46");
        // 价格按收购日期当天的基准价（昨天 178）
        assertThat(a.get("submittedPrice").decimalValue()).isEqualByComparingTo("179");

        JsonNode b = call(HttpMethod.POST, "/api/orders", body, admin()).getBody();
        assertThat(b.get("id").asLong()).isEqualTo(a.get("id").asLong());
        assertThat(count("SELECT COUNT(*) FROM purchase_order")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM weigh_record")).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM weigh_record WHERE manual = 1 OR raw_kg IS NOT NULL")).isZero();
        assertThat(jdbc.queryForObject("SELECT biz_date FROM purchase_order", java.sql.Date.class).toString())
                .isEqualTo(today.minusDays(1).toString());
    }

    @Test
    void simultaneousSubmitsOfTheSameBatchNoMakeOneOrder() throws Exception {
        String batchNo = newBatch(scale());
        var body = submission(batchNo, liSi, pink, s360, LI_SI_BOXES);
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<ResponseEntity<JsonNode>>> jobs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                jobs.add(() -> call(HttpMethod.POST, "/api/orders", body, scale()));
            }
            for (Future<ResponseEntity<JsonNode>> f : pool.invokeAll(jobs)) {
                assertThat(f.get().getStatusCode().value()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(count("SELECT COUNT(*) FROM purchase_order")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM weigh_record")).isEqualTo(10);
    }

    @Test
    void missingRuleOrBasePriceBlocksSubmitByDefaultAndAllowOptionLetsItThrough() {
        String batchNo = newBatch(scale());
        // 王秀英没有规则，480 又没有基准价
        var body = submission(batchNo, wangXiuYing, pink, s480, List.of(60.1, 59.9, 60.0));

        var blocked = call(HttpMethod.POST, "/api/orders", body, scale());
        assertThat(blocked.getStatusCode().value()).isEqualTo(409);
        assertThat(blocked.getBody().get("code").asText()).isEqualTo("NOT_READY");
        assertThat(count("SELECT COUNT(*) FROM purchase_order")).isZero();

        jdbc.update("INSERT INTO `option` (opt_key, opt_value) VALUES ('submit.without-price', 'allow')");
        var ok = call(HttpMethod.POST, "/api/orders", body, scale());
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().get("submittedPrice").isNull()).isTrue();
        assertThat(ok.getBody().get("submittedAmount").isNull()).isTrue();
        assertThat(ok.getBody().get("boxCount").asInt()).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM purchase_order")).isEqualTo(1);
    }

    @Test
    void invalidInputIsRejectedWithoutWritingAnything() {
        String batchNo = newBatch(scale());
        var ok = submission(batchNo, liSi, pink, s360, List.of(46.0));

        var badBatch = new java.util.HashMap<>(ok);
        badBatch.put("batchNo", "ABC-1");
        assertThat(call(HttpMethod.POST, "/api/orders", badBatch, scale()).getStatusCode().value()).isEqualTo(400);

        var noBoxes = new java.util.HashMap<>(ok);
        noBoxes.put("boxes", List.of());
        assertThat(call(HttpMethod.POST, "/api/orders", noBoxes, scale()).getStatusCode().value()).isEqualTo(400);

        var badVendor = new java.util.HashMap<>(ok);
        badVendor.put("vendorId", 999999);
        var res = call(HttpMethod.POST, "/api/orders", badVendor, scale());
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("message").asText()).contains("农户");

        var dupSeq = new java.util.HashMap<>(ok);
        dupSeq.put("boxes", List.of(box(1, 46.0), box(1, 46.0)));
        res = call(HttpMethod.POST, "/api/orders", dupSeq, scale());
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("message").asText()).contains("重复");

        var zeroNet = new java.util.HashMap<>(ok);
        zeroNet.put("boxes", List.of(box(1, 0)));
        assertThat(call(HttpMethod.POST, "/api/orders", zeroNet, scale()).getStatusCode().value()).isEqualTo(400);

        assertThat(count("SELECT COUNT(*) FROM purchase_order")).isZero();
        assertThat(count("SELECT COUNT(*) FROM weigh_record")).isZero();
    }

    @Test
    void submitNeedsLogin() {
        var body = submission("SG20261115-1", liSi, pink, s360, List.of(46.0));
        assertThat(call(HttpMethod.POST, "/api/orders", body, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/batches", null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/orders/quote", Map.of(), null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void redIsAlsoAProductButHasNoRuleForLiSi() {
        // 规则按“商品 + 规格”区分：李四只有粉蛋的规则，红蛋缺规则，默认不能提交
        var q = call(HttpMethod.POST, "/api/orders/quote", Map.of("vendorId", liSi, "productId", red, "specId", s360,
                "boxNetKg", List.of(46.0)), scale()).getBody();
        assertThat(q.get("issues")).extracting(JsonNode::asText).containsExactly("NO_RULE");
    }
}

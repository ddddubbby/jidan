package com.egg.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * P2-3 验收：李四单提交后按箱码查到第 3 箱；改一箱净重后金额变化、该箱为手录并保留原值；改规则系数后详情金额变化
 * 且仍返回提交时金额；非待审核单修改返回 ORDER_LOCKED；Web 自建 10 箱 460.5kg 得数量 10、均重 46。
 * 另覆盖列表页签与搜索、导航计数、逐箱流水的同重归类、冻结单读冻结值、下一张待审核。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.admin.phone=13700000021", "egg.admin.password=boss-pass-3", "egg.admin.name=单据查询测试管理员"})
@Import(MySqlTestConfig.class)
class OrderQueryTest {

    /** v7 6.1、6.4：李四 10 箱粉蛋，合计 460.5kg，均重 46，181 元/箱，1810 元 */
    private static final List<Double> LI_SI_BOXES = List.of(46.5, 45.8, 46.0, 46.0, 46.0, 45.6, 45.6, 45.6, 45.6, 47.8);

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;

    private static volatile String adminToken;
    private static volatile String scaleToken;

    private long pink, red, s360, s480, liSi, wangXiuYing, warehouse, rule;
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
        jdbc.update("INSERT INTO vendor_phone (vendor_id, phone, tail4) VALUES (?, '13603511234', '1234')", liSi);
        jdbc.update("INSERT INTO vendor_phone (vendor_id, phone, tail4) VALUES (?, '13903515678', '5678')", wangXiuYing);
        warehouse = insert("INSERT INTO warehouse (hs_code, name) VALUES ('CK0001', '一号库')");
        // 360 当日有基准价、480 没有
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 180)", today, s360);
        rule = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, coef, enabled)"
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
            adminToken = token("13700000021", "boss-pass-3", "WEB");
        }
        return adminToken;
    }

    private String scale() {
        if (scaleToken == null) {
            JsonNode created = call(HttpMethod.POST, "/api/accounts", Map.of("name", "测试磅秤员", "phone", "13700000022"),
                    admin()).getBody();
            scaleToken = token("13700000022", created.get("password").asText(), "APP");
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

    /** 箱码里的 # 要编码；用 URI 模板变量让 RestTemplate 编一次。 */
    private ResponseEntity<JsonNode> lookup(String code, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return http.exchange("/api/boxes/lookup?code={c}", HttpMethod.GET, new HttpEntity<>(null, h), JsonNode.class,
                Map.of("c", code));
    }

    private JsonNode get(String url, Object... vars) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(admin());
        var res = http.exchange(url, HttpMethod.GET, new HttpEntity<>(null, h), JsonNode.class, vars);
        assertThat(res.getStatusCode().value()).as(url).isEqualTo(200);
        return res.getBody();
    }

    private static Map<String, Object> box(int seq, double net, boolean manual, Double raw) {
        Map<String, Object> m = new HashMap<>();
        m.put("seq", seq);
        m.put("netKg", net);
        m.put("grossKg", net + 2);
        m.put("tareKg", 2);
        m.put("tareName", "2号包装箱");
        m.put("groupNo", seq);
        m.put("groupK", 1);
        m.put("manual", manual);
        m.put("rawKg", raw);
        m.put("printed", true);
        return m;
    }

    private static List<Map<String, Object>> boxes(List<Double> nets) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < nets.size(); i++) {
            out.add(box(i + 1, nets.get(i), false, null));
        }
        return out;
    }

    /** 取批次号并提交，返回单据 id。token 决定来源：磅秤员 APP，管理员 WEB。 */
    private long submit(String token, long vendor, long product, long spec, List<Double> nets) {
        String batchNo = call(HttpMethod.POST, "/api/batches", null, token).getBody().get("batchNo").asText();
        var res = call(HttpMethod.POST, "/api/orders", Map.of("batchNo", batchNo, "vendorId", vendor, "productId", product,
                "specId", spec, "warehouseId", warehouse, "boxes", boxes(nets)), token);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody().get("id").asLong();
    }

    private Map<String, Object> replacement(LocalDate date, long vendor, List<Map<String, Object>> boxes) {
        return Map.of("bizDate", date.toString(), "vendorId", vendor, "productId", pink, "specId", s360,
                "warehouseId", warehouse, "boxes", boxes);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---------------- 箱码查询 ----------------

    @Test
    void boxCodeFindsThirdBoxOfLiSiOrder() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        String batchNo = get("/api/orders/" + id).get("batchNo").asText();

        // 磅秤员与管理员都能查
        for (String token : List.of(admin(), scale())) {
            var res = lookup(batchNo + "#3", token);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            JsonNode b = res.getBody();
            assertThat(b.get("code").asText()).isEqualTo(batchNo + "#3");
            assertThat(b.get("batchNo").asText()).isEqualTo(batchNo);
            assertThat(b.get("seq").asInt()).isEqualTo(3);
            assertThat(b.get("netKg").decimalValue()).isEqualByComparingTo("46");
            assertThat(b.get("manual").asBoolean()).isFalse();
            assertThat(b.get("avgKg").decimalValue()).isEqualByComparingTo("46");
            assertThat(b.get("price").decimalValue()).isEqualByComparingTo("181");
            assertThat(b.get("vendor").get("name").asText()).isEqualTo("李四");
            assertThat(b.get("product").get("name").asText()).isEqualTo("粉蛋");
            assertThat(b.get("spec").get("name").asText()).isEqualTo("360枚/箱");
            assertThat(b.get("status").asText()).isEqualTo("PENDING");
            assertThat(b.get("orderId").asLong()).isEqualTo(id);
        }
    }

    @Test
    void unknownBoxCodeIsNotFound() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        String batchNo = get("/api/orders/" + id).get("batchNo").asText();
        for (String code : List.of(batchNo + "#11", batchNo + "#0", "SG19990101-1#1", "garbage", batchNo)) {
            var res = lookup(code, admin());
            assertThat(res.getStatusCode().value()).as(code).isEqualTo(404);
            assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        }
        assertThat(lookup("x", null).getStatusCode().value()).isEqualTo(401);
    }

    // ---------------- 整单修改 ----------------

    @Test
    void editingABoxChangesAmountAndKeepsTheOriginalReading() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        assertThat(get("/api/orders/" + id).get("pricing").get("amount").decimalValue()).isEqualByComparingTo("1810");

        // v7 6.6：第 10 箱秤读数 47.8，现场手录成 57.8，均重 47，金额 1820。前端标手录并把原读数记在 rawKg
        List<Map<String, Object>> edited = boxes(LI_SI_BOXES);
        edited.set(9, box(10, 57.8, true, 47.8));
        var res = call(HttpMethod.PUT, "/api/orders/" + id, replacement(today, liSi, edited), admin());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode d = res.getBody();
        assertThat(d.get("pricing").get("totalKg").decimalValue()).isEqualByComparingTo("470.5");
        assertThat(d.get("pricing").get("avgKg").decimalValue()).isEqualByComparingTo("47");
        assertThat(d.get("pricing").get("unitPrice").decimalValue()).isEqualByComparingTo("182");
        assertThat(d.get("pricing").get("amount").decimalValue()).isEqualByComparingTo("1820");
        assertThat(d.get("manualCount").asInt()).isEqualTo(1);
        // 提交时的金额不变，并标出“与提交时不同”
        assertThat(d.get("submittedAmount").decimalValue()).isEqualByComparingTo("1810");
        assertThat(d.get("submittedDiffers").asBoolean()).isTrue();

        JsonNode recs = get("/api/orders/" + id + "/records");
        JsonNode tenth = recs.get("boxes").get(9);
        assertThat(tenth.get("seq").asInt()).isEqualTo(10);
        assertThat(tenth.get("netKg").decimalValue()).isEqualByComparingTo("57.8");
        assertThat(tenth.get("manual").asBoolean()).isTrue();
        assertThat(tenth.get("rawKg").decimalValue()).isEqualByComparingTo("47.8");
        assertThat(recs.get("manualCount").asInt()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM weigh_record WHERE order_id = ?", id)).isEqualTo(10);

        // 已经是手录的箱再改：前端保持原读数不变，后端照存
        edited.set(9, box(10, 58.8, true, 47.8));
        var again = call(HttpMethod.PUT, "/api/orders/" + id, replacement(today, liSi, edited), admin()).getBody();
        assertThat(again.get("pricing").get("totalKg").decimalValue()).isEqualByComparingTo("471.5");
        assertThat(get("/api/orders/" + id + "/records").get("boxes").get(9).get("rawKg").decimalValue())
                .isEqualByComparingTo("47.8");
    }

    /** F1-6：Web 删箱不重排序号。箱贴在收蛋时已打印，二维码是 批次号#序号，其余箱的序号必须原样保留。 */
    @Test
    void removingABoxKeepsTheOtherSeqsSoPrintedLabelsStillResolve() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        String batchNo = get("/api/orders/" + id).get("batchNo").asText();

        // 去掉第 3 箱，其余序号不变（4..10 仍是 4..10）
        List<Map<String, Object>> kept = new ArrayList<>(boxes(LI_SI_BOXES));
        kept.remove(2);
        var res = call(HttpMethod.PUT, "/api/orders/" + id, replacement(today, liSi, kept), admin());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode d = res.getBody();
        assertThat(d.get("pricing").get("boxCount").asInt()).isEqualTo(9);
        // 总重 460.5 - 46 = 414.5，箱数 9，均重与金额随之重算
        assertThat(d.get("pricing").get("totalKg").decimalValue()).isEqualByComparingTo("414.5");
        assertThat(d.get("pricing").get("amount").decimalValue()).isNotEqualByComparingTo("1810");

        // 箱贴上的 #4 仍是原第 4 箱（净重 46），#3 已经没有这一箱
        var four = lookup(batchNo + "#4", admin());
        assertThat(four.getStatusCode().value()).isEqualTo(200);
        assertThat(four.getBody().get("seq").asInt()).isEqualTo(4);
        assertThat(four.getBody().get("netKg").decimalValue()).isEqualByComparingTo("46");
        var ten = lookup(batchNo + "#10", admin());
        assertThat(ten.getStatusCode().value()).isEqualTo(200);
        assertThat(ten.getBody().get("netKg").decimalValue()).isEqualByComparingTo("47.8");
        var three = lookup(batchNo + "#3", admin());
        assertThat(three.getStatusCode().value()).isEqualTo(404);
        assertThat(three.getBody().get("code").asText()).isEqualTo("NOT_FOUND");

        // 流水按序号返回，序号带空号：1、2、4..10
        JsonNode recs = get("/api/orders/" + id + "/records").get("boxes");
        assertThat(recs).extracting(b -> b.get("seq").asInt()).containsExactly(1, 2, 4, 5, 6, 7, 8, 9, 10);

        // 再加一箱取现有最大序号加 1（11），不是箱数加 1（10）
        kept.add(box(11, 45.0, true, null));
        var added = call(HttpMethod.PUT, "/api/orders/" + id, replacement(today, liSi, kept), admin());
        assertThat(added.getStatusCode().value()).isEqualTo(200);
        assertThat(added.getBody().get("pricing").get("boxCount").asInt()).isEqualTo(10);
        assertThat(lookup(batchNo + "#11", admin()).getBody().get("netKg").decimalValue()).isEqualByComparingTo("45");
    }

    @Test
    void editReplacesHeaderAndBoxesAsAWholeButKeepsBatchNoAndCode() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        JsonNode before = get("/api/orders/" + id);
        LocalDate yesterday = today.minusDays(1);

        // 删掉两箱、换农户、改日期（昨天没有基准价）
        var res = call(HttpMethod.PUT, "/api/orders/" + id,
                replacement(yesterday, wangXiuYing, boxes(List.of(46.0, 46.0, 46.0))), admin());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode d = res.getBody();
        assertThat(d.get("batchNo").asText()).isEqualTo(before.get("batchNo").asText());
        assertThat(d.get("code").asText()).isEqualTo(before.get("code").asText());
        assertThat(d.get("bizDate").asText()).isEqualTo(yesterday.toString());
        assertThat(d.get("vendor").get("name").asText()).isEqualTo("王秀英");
        assertThat(d.get("pricing").get("boxCount").asInt()).isEqualTo(3);
        assertThat(d.get("pricing").get("issues")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("NO_RULE", "NO_BASE_PRICE");
        assertThat(d.get("pricing").get("amount").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM weigh_record WHERE order_id = ?", id)).isEqualTo(3);
        assertThat(d.get("submittedAmount").decimalValue()).isEqualByComparingTo("1810");
    }

    @Test
    void editRejectsDuplicateSeqAndEmptyBoxesAndLeavesOrderUntouched() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        List<Map<String, Object>> dup = boxes(List.of(46.0, 46.0));
        dup.set(1, box(1, 47.0, false, null));
        var res = call(HttpMethod.PUT, "/api/orders/" + id, replacement(today, liSi, dup), admin());
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID");
        assertThat(call(HttpMethod.PUT, "/api/orders/" + id, replacement(today, liSi, List.of()), admin())
                .getStatusCode().value()).isEqualTo(400);
        // 事务整体回滚：流水还是原来的 10 箱
        assertThat(count("SELECT COUNT(*) FROM weigh_record WHERE order_id = ?", id)).isEqualTo(10);
        assertThat(call(HttpMethod.PUT, "/api/orders/999999", replacement(today, liSi, boxes(List.of(46.0))), admin())
                .getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void nonPendingOrdersCannotBeEdited() {
        for (String status : List.of("APPROVED", "PUSHED", "VOID")) {
            long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
            jdbc.update("UPDATE purchase_order SET status = ? WHERE id = ?", status, id);
            var res = call(HttpMethod.PUT, "/api/orders/" + id,
                    replacement(today, liSi, boxes(List.of(46.0))), admin());
            assertThat(res.getStatusCode().value()).as(status).isEqualTo(409);
            assertThat(res.getBody().get("code").asText()).isEqualTo("ORDER_LOCKED");
            assertThat(count("SELECT COUNT(*) FROM weigh_record WHERE order_id = ?", id)).isEqualTo(10);
        }
    }

    // ---------------- Web 自建单 ----------------

    @Test
    void webCreatedOrderOf460Point5KgGivesTenBoxesAndAverage46() {
        long id = submit(admin(), liSi, pink, s360, LI_SI_BOXES);
        JsonNode d = get("/api/orders/" + id);
        assertThat(d.get("source").asText()).isEqualTo("WEB");
        assertThat(d.get("pricing").get("boxCount").asInt()).isEqualTo(10);
        assertThat(d.get("pricing").get("totalKg").decimalValue()).isEqualByComparingTo("460.5");
        assertThat(d.get("pricing").get("avgKg").decimalValue()).isEqualByComparingTo("46");
        assertThat(d.get("pricing").get("unitPrice").decimalValue()).isEqualByComparingTo("181");
        assertThat(d.get("pricing").get("amount").decimalValue()).isEqualByComparingTo("1810");
        assertThat(d.get("submittedDiffers").asBoolean()).isFalse();
        assertThat(d.get("operator").get("name").asText()).isEqualTo("单据查询测试管理员");
        assertThat(d.get("manualCount").asInt()).isZero();
    }

    @Test
    void editingAWebOrderNeverMarksBoxesAsManual() {
        long id = submit(admin(), liSi, pink, s360, LI_SI_BOXES);
        List<Map<String, Object>> edited = boxes(LI_SI_BOXES);
        edited.set(0, box(1, 50.0, true, 46.5));
        call(HttpMethod.PUT, "/api/orders/" + id, replacement(today, liSi, edited), admin());
        JsonNode first = get("/api/orders/" + id + "/records").get("boxes").get(0);
        assertThat(first.get("netKg").decimalValue()).isEqualByComparingTo("50");
        assertThat(first.get("manual").asBoolean()).isFalse();
        assertThat(first.get("rawKg").isNull()).isTrue();
        assertThat(first.get("grossKg").isNull()).isTrue();
    }

    // ---------------- 详情、冻结 ----------------

    @Test
    void changingRuleCoefficientChangesPendingDetailButNotSubmittedAmount() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        var put = call(HttpMethod.PUT, "/api/rules/" + rule, Map.of("coef", 2), admin());
        assertThat(put.getStatusCode().value()).isEqualTo(200);

        JsonNode d = get("/api/orders/" + id);
        assertThat(d.get("frozen").asBoolean()).isFalse();
        assertThat(d.get("pricing").get("unitPrice").decimalValue()).isEqualByComparingTo("182");
        assertThat(d.get("pricing").get("amount").decimalValue()).isEqualByComparingTo("1820");
        assertThat(d.get("submittedPrice").decimalValue()).isEqualByComparingTo("181");
        assertThat(d.get("submittedAmount").decimalValue()).isEqualByComparingTo("1810");
        assertThat(d.get("submittedDiffers").asBoolean()).isTrue();
        assertThat(d.get("pricing").get("ruleName").asText()).isEqualTo("粉蛋李四收购定价方案");
        assertThat(d.get("vendor").get("phones").get(0).asText()).isEqualTo("13603511234");
        assertThat(d.get("warehouse").get("name").asText()).isEqualTo("一号库");
        assertThat(d.get("spec").get("stdNetKg").decimalValue()).isEqualByComparingTo("45");

        // 列表里也标出“原金额”
        JsonNode row = get("/api/orders?tab=pending").get("items").get(0);
        assertThat(row.get("amount").decimalValue()).isEqualByComparingTo("1820");
        assertThat(row.get("submittedAmount").decimalValue()).isEqualByComparingTo("1810");
        assertThat(row.get("amountChanged").asBoolean()).isTrue();
    }

    @Test
    void approvedOrderReadsFrozenValuesNotTheCurrentRule() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        // 审核由 P2-4 做；这里直接写入审核时冻结的那组字段，验证读取走冻结值
        jdbc.update("UPDATE purchase_order SET status = 'APPROVED', round_mode = 'HALF', box_count = 10,"
                + " total_net_kg = 460.5, avg_kg = 46, std_net_kg = 45, base_price = 180, rule_id = ?,"
                + " rule_name = '粉蛋李四收购定价方案', rule_coef = 1, price = 181, amount = 1810 WHERE id = ?", rule, id);
        call(HttpMethod.PUT, "/api/rules/" + rule, Map.of("coef", 2), admin());
        jdbc.update("UPDATE base_price SET base_price = 200");

        JsonNode d = get("/api/orders/" + id);
        assertThat(d.get("frozen").asBoolean()).isTrue();
        assertThat(d.get("status").asText()).isEqualTo("APPROVED");
        JsonNode p = d.get("pricing");
        assertThat(p.get("basePrice").decimalValue()).isEqualByComparingTo("180");
        assertThat(p.get("coefficient").decimalValue()).isEqualByComparingTo("1");
        assertThat(p.get("avgKg").decimalValue()).isEqualByComparingTo("46");
        assertThat(p.get("tailKg").decimalValue()).isEqualByComparingTo("1");
        assertThat(p.get("priceDiff").decimalValue()).isEqualByComparingTo("1");
        assertThat(p.get("unitPrice").decimalValue()).isEqualByComparingTo("181");
        assertThat(p.get("amount").decimalValue()).isEqualByComparingTo("1810");
        assertThat(d.get("submittedDiffers").asBoolean()).isFalse();
    }

    @Test
    void detailOfUnknownOrderIsNotFound() {
        var res = call(HttpMethod.GET, "/api/orders/999999", null, admin());
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call(HttpMethod.GET, "/api/orders/999999/records", null, admin()).getStatusCode().value())
                .isEqualTo(404);
    }

    // ---------------- 流水 ----------------

    @Test
    void recordsGroupSameWeightsMostBoxesFirstThenFirstSeen() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        JsonNode recs = get("/api/orders/" + id + "/records");
        assertThat(recs.get("editable").asBoolean()).isTrue();
        assertThat(recs.get("boxes")).hasSize(10);
        assertThat(recs.get("boxes").get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(recs.get("boxes").get(0).get("tareName").asText()).isEqualTo("2号包装箱");
        // v7 6.3：45.6kg-4箱,46kg-3箱,46.5kg-1箱,45.8kg-1箱,47.8kg-1箱
        List<String> groups = new ArrayList<>();
        recs.get("groups").forEach(g -> groups.add(g.get("kg").decimalValue().toPlainString() + "/" + g.get("boxes").asInt()));
        assertThat(groups).containsExactly("45.6/4", "46/3", "46.5/1", "45.8/1", "47.8/1");

        jdbc.update("UPDATE purchase_order SET status = 'APPROVED' WHERE id = ?", id);
        assertThat(get("/api/orders/" + id + "/records").get("editable").asBoolean()).isFalse();
    }

    @Test
    void sameWeightGroupingIgnoresTrailingZerosAndHundredsStayPlain() {
        var groups = SameWeight.of(List.of(new BigDecimal("46.00"), new BigDecimal("46"), new BigDecimal("100"),
                new BigDecimal("45.6")));
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).kg().toPlainString()).isEqualTo("46");
        assertThat(groups.get(0).boxes()).isEqualTo(2);
        assertThat(groups.get(1).kg().toString()).isEqualTo("100");
    }

    // ---------------- 列表与计数 ----------------

    @Test
    void listTabsSearchAndCounts() {
        // 王秀英那张缺规则缺价，要放行才能提交
        jdbc.update("INSERT INTO `option` (opt_key, opt_value) VALUES ('submit.without-price', 'allow')");
        long pendingLiSi = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        long pendingWang = submit(scale(), wangXiuYing, red, s480, List.of(60.1, 59.9));
        long failed = submit(scale(), liSi, pink, s360, List.of(46.0, 46.0));
        long sent = submit(scale(), liSi, pink, s360, List.of(46.0, 46.0));
        long voided = submit(scale(), liSi, pink, s360, List.of(46.0));
        jdbc.update("UPDATE purchase_order SET status = 'APPROVED', push_error = '好生意返回：库存不足' WHERE id = ?", failed);
        jdbc.update("UPDATE purchase_order SET status = 'PUSHED', hs_id = 'HS1' WHERE id = ?", sent);
        jdbc.update("UPDATE purchase_order SET status = 'VOID' WHERE id = ?", voided);

        assertThat(ids(get("/api/orders?tab=pending"))).containsExactlyInAnyOrder(pendingLiSi, pendingWang);
        assertThat(ids(get("/api/orders"))).containsExactlyInAnyOrder(pendingLiSi, pendingWang);
        assertThat(ids(get("/api/orders?tab=failed"))).containsExactly(failed);
        assertThat(ids(get("/api/orders?tab=unsent"))).containsExactly(failed);
        assertThat(ids(get("/api/orders?tab=sent"))).containsExactly(sent);
        JsonNode all = get("/api/orders?tab=all");
        assertThat(all.get("total").asInt()).isEqualTo(5);
        // 最新提交的在前
        assertThat(ids(all)).containsExactly(voided, sent, failed, pendingWang, pendingLiSi);

        // 待审核的一行：缺规则缺价的标出缺项，金额为空
        JsonNode wang = byId(get("/api/orders?tab=pending"), pendingWang);
        assertThat(wang.get("vendorName").asText()).isEqualTo("王秀英");
        assertThat(wang.get("vendorPhone").asText()).isEqualTo("13903515678");
        assertThat(wang.get("specName").asText()).isEqualTo("480枚/箱");
        assertThat(wang.get("boxCount").asInt()).isEqualTo(2);
        assertThat(wang.get("issues")).extracting(JsonNode::asText).containsExactlyInAnyOrder("NO_RULE", "NO_BASE_PRICE");
        assertThat(wang.get("amount").isNull()).isTrue();
        assertThat(wang.get("operatorName").asText()).isEqualTo("测试磅秤员");
        JsonNode li = byId(get("/api/orders?tab=pending"), pendingLiSi);
        assertThat(li.get("amount").decimalValue()).isEqualByComparingTo("1810");
        assertThat(li.get("avgKg").decimalValue()).isEqualByComparingTo("46");
        assertThat(byId(get("/api/orders?tab=failed"), failed).get("pushError").asText()).contains("库存不足");

        // 搜索：农户名、手机号（任意一段）、单据编号、批次号
        assertThat(ids(get("/api/orders?tab=all&q={q}", "王秀英"))).containsExactly(pendingWang);
        assertThat(ids(get("/api/orders?tab=pending&q=5678"))).containsExactly(pendingWang);
        assertThat(ids(get("/api/orders?tab=pending&q=13603511234"))).containsExactly(pendingLiSi);
        String code = get("/api/orders/" + pendingWang).get("code").asText();
        String batchNo = get("/api/orders/" + pendingWang).get("batchNo").asText();
        assertThat(ids(get("/api/orders?tab=all&q=" + code))).containsExactly(pendingWang);
        assertThat(ids(get("/api/orders?tab=all&q=" + batchNo))).containsExactly(pendingWang);
        assertThat(ids(get("/api/orders?tab=all&q={q}", "没有这个人"))).isEmpty();

        // 分页
        JsonNode page2 = get("/api/orders?tab=all&page=2&pageSize=2");
        assertThat(page2.get("total").asInt()).isEqualTo(5);
        assertThat(ids(page2)).containsExactly(failed, pendingWang);

        assertThat(call(HttpMethod.GET, "/api/orders?tab=bogus", null, admin()).getStatusCode().value()).isEqualTo(400);

        // 导航计数：待审核 2、回传失败 1、480 当日没录基准价
        JsonNode c = get("/api/orders/counts");
        assertThat(c.get("pending").asInt()).isEqualTo(2);
        assertThat(c.get("failed").asInt()).isEqualTo(1);
        assertThat(c.get("unsent").asInt()).isEqualTo(1);
        assertThat(c.get("missingBasePrices")).hasSize(1);
        assertThat(c.get("missingBasePrices").get(0).get("specName").asText()).isEqualTo("480枚/箱");
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 240)", today, s480);
        assertThat(get("/api/orders/counts").get("missingBasePrices")).isEmpty();
    }

    @Test
    void waitingToPushOrderIsNotCountedAsFailed() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        jdbc.update("UPDATE purchase_order SET status = 'APPROVED' WHERE id = ?", id);
        assertThat(get("/api/orders/counts").get("failed").asInt()).isZero();
        assertThat(ids(get("/api/orders?tab=failed"))).isEmpty();
        // “待回传”页签（unsent）与计数把它算进去
        assertThat(get("/api/orders/counts").get("unsent").asInt()).isEqualTo(1);
        assertThat(ids(get("/api/orders?tab=unsent"))).containsExactly(id);
        assertThat(ids(get("/api/orders?tab=all"))).containsExactly(id);
    }

    // ---------------- 下一张待审核 ----------------

    @Test
    void nextPendingFollowsTheOrderAfterTheGivenOne() {
        long a = submit(scale(), liSi, pink, s360, List.of(46.0));
        long b = submit(scale(), liSi, pink, s360, List.of(46.0));
        long c = submit(scale(), liSi, pink, s360, List.of(46.0));
        // 让三张单的提交时间有先后（同一秒内提交时靠 id 排）
        assertThat(get("/api/orders/next-pending").get("id").asLong()).isEqualTo(a);
        assertThat(get("/api/orders/next-pending?after=" + a).get("id").asLong()).isEqualTo(b);
        assertThat(get("/api/orders/next-pending?after=" + b).get("id").asLong()).isEqualTo(c);
        // 最后一张之后回到最前面那张，不返回它自己
        assertThat(get("/api/orders/next-pending?after=" + c).get("id").asLong()).isEqualTo(a);
        JsonNode n = get("/api/orders/next-pending?after=" + a);
        assertThat(n.get("vendorName").asText()).isEqualTo("李四");
        assertThat(n.get("code").asText()).startsWith("CGRK");

        // 刚审核完的 b 已不在待审核里：下一张仍是排在它后面的 c
        jdbc.update("UPDATE purchase_order SET status = 'APPROVED' WHERE id = ?", b);
        assertThat(get("/api/orders/next-pending?after=" + b).get("id").asLong()).isEqualTo(c);

        // 只剩它自己时没有下一张
        jdbc.update("UPDATE purchase_order SET status = 'PUSHED' WHERE id IN (?, ?)", a, c);
        long last = submit(scale(), liSi, pink, s360, List.of(46.0));
        JsonNode none = get("/api/orders/next-pending?after=" + last);
        assertThat(none.get("id").isNull()).isTrue();
    }

    // ---------------- 权限 ----------------

    @Test
    void queryAndEditEndpointsAreAdminOnly() {
        long id = submit(scale(), liSi, pink, s360, LI_SI_BOXES);
        for (String url : List.of("/api/orders", "/api/orders/counts", "/api/orders/next-pending", "/api/orders/" + id,
                "/api/orders/" + id + "/records")) {
            assertThat(call(HttpMethod.GET, url, null, scale()).getStatusCode().value()).as(url).isEqualTo(403);
            assertThat(call(HttpMethod.GET, url, null, null).getStatusCode().value()).as(url).isEqualTo(401);
        }
        assertThat(call(HttpMethod.PUT, "/api/orders/" + id, replacement(today, liSi, boxes(List.of(46.0))), scale())
                .getStatusCode().value()).isEqualTo(403);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asLong()));
        return out;
    }

    private static JsonNode byId(JsonNode page, long id) {
        for (JsonNode i : page.get("items")) {
            if (i.get("id").asLong() == id) {
                return i;
            }
        }
        throw new AssertionError("列表里没有单据 " + id);
    }
}

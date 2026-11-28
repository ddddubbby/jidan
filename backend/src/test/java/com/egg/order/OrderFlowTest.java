package com.egg.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.egg.MySqlTestConfig;
import com.egg.chanjet.ChanjetClient;
import com.egg.chanjet.FakeChanjetClient;
import com.egg.chanjet.FakeChanjetClient.Failure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * P2-4 验收（只在模拟好生意上验证，未经实物验证）：审核冻结、回传失败与重传、退回、标记为已回传、作废、
 * 同时审核只回传一次、push.on-approve 与 push.remark 的各种取值。
 * “退回后可修改”与“作废后不可修改”要用 P2-3 的整单修改接口，这里只断言状态与冻结字段已清空；修改的断言由后合入的任务补。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.admin.phone=13700000021", "egg.admin.password=boss-pass-3", "egg.admin.name=回传测试管理员"})
@Import(MySqlTestConfig.class)
class OrderFlowTest {

    /** v7 6.1、6.4：李四 10 箱粉蛋，合计 460.5斤，均重 46，181 元/箱，1810 元 */
    private static final List<Double> LI_SI_BOXES = List.of(46.5, 45.8, 46.0, 46.0, 46.0, 45.6, 45.6, 45.6, 45.6, 47.8);
    /** v7 6.3 的备注全文 */
    private static final String REMARK_FULL = "共10箱；逐箱：46.5,45.8,46,46,46,45.6,45.6,45.6,45.6,47.8；"
            + "归类：45.6斤-4箱,46斤-3箱,46.5斤-1箱,45.8斤-1箱,47.8斤-1箱";
    private static final String REMARK_SUMMARY = "共10箱；归类：45.6斤-4箱,46斤-3箱,46.5斤-1箱,45.8斤-1箱,47.8斤-1箱";

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ChanjetClient chanjet;
    @Autowired
    ObjectMapper json;

    private static volatile String adminToken;
    private static volatile String scaleToken;

    private FakeChanjetClient fake;
    private long pink, s360, s480, liSi, warehouse, rule;
    private final java.time.LocalDate today = java.time.LocalDate.now();

    @BeforeEach
    void fixtures() {
        for (String t : List.of("weigh_record", "purchase_order", "batch_seq", "pricing_rule",
                "base_price", "vendor_phone", "vendor", "warehouse", "product", "spec", "tare_box", "`option`")) {
            jdbc.update("DELETE FROM " + t);
        }
        fake = (FakeChanjetClient) chanjet;
        fake.failNext(null);
        pink = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0001', '粉蛋', '箱')");
        s360 = insert("INSERT INTO spec (name, egg_count, std_net_jin, enabled) VALUES ('360枚/箱', 360, 45, 1)");
        s480 = insert("INSERT INTO spec (name, egg_count, std_net_jin, enabled) VALUES ('480枚/箱', 480, 60, 1)");
        liSi = insert("INSERT INTO vendor (hs_code, name) VALUES ('GYS0001', '李四')");
        warehouse = insert("INSERT INTO warehouse (hs_code, name) VALUES ('CK0001', '一号库')");
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 180)", today, s360);
        rule = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, coef, enabled)"
                + " VALUES ('粉蛋李四收购定价方案', 'BUY', " + pink + ", " + s360 + ", 1, 1)");
    }

    // ---------------------------------------------------------------- 工具

    /** 规则改为建单时选（附录 E R-1 行）：取这个商品规格上的第一条收购规则，没有就建一条系数 1 的。 */
    private long ruleOf(long product, long spec) {
        List<Long> found = jdbc.queryForList("SELECT id FROM pricing_rule WHERE kind = 'BUY' AND product_id = ? AND spec_id = ?"
                + " ORDER BY id LIMIT 1", Long.class, product, spec);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, coef, enabled) VALUES ('测试规则"
                + product + "-" + spec + "', 'BUY', " + product + ", " + spec + ", 1, 1)");
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
            JsonNode created = call(HttpMethod.POST, "/api/accounts", Map.of("name", "回传测试磅秤员", "phone", "13700000022"),
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

    private JsonNode post(long id, String action) {
        return call(HttpMethod.POST, "/api/orders/" + id + "/" + action, null, admin()).getBody();
    }

    private int postStatus(long id, String action) {
        return call(HttpMethod.POST, "/api/orders/" + id + "/" + action, null, admin()).getStatusCode().value();
    }

    private void setOption(String key, String value) {
        jdbc.update("INSERT INTO `option` (opt_key, opt_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE opt_value = ?",
                key, value, value);
    }

    private Map<String, Object> box(int seq, double net) {
        Map<String, Object> m = new HashMap<>();
        m.put("seq", seq);
        m.put("netJin", net);
        m.put("grossJin", net + 2);
        m.put("tareJin", 2);
        m.put("tareName", "2号包装箱");
        m.put("groupNo", seq);
        m.put("groupK", 1);
        m.put("manual", false);
        m.put("printed", true);
        return m;
    }

    /** 以磅秤员身份提交一张单，返回单据 id。 */
    private long submit(long spec, List<Double> nets) {
        String batchNo = call(HttpMethod.POST, "/api/batches", null, scale()).getBody().get("batchNo").asText();
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (int i = 0; i < nets.size(); i++) {
            boxes.add(box(i + 1, nets.get(i)));
        }
        var res = call(HttpMethod.POST, "/api/orders", Map.of("batchNo", batchNo, "vendorId", liSi, "ruleId", ruleOf(pink, spec), "warehouseId", warehouse, "boxes", boxes), scale());
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return res.getBody().get("id").asLong();
    }

    private long submitLiSi() {
        return submit(s360, LI_SI_BOXES);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM purchase_order WHERE id = ?", id);
    }

    private JsonNode sentPayload(int index) throws Exception {
        return json.readTree(fake.sentPayloads().get(index));
    }

    // ---------------------------------------------------------------- 审核与冻结

    @Test
    void approveFreezesEverythingAndPushesOnce() throws Exception {
        long id = submitLiSi();
        int before = fake.sentPayloads().size();

        JsonNode res = post(id, "approve");

        // push.on-approve 默认 auto：审核后紧接着回传，成功转已回传
        assertThat(res.get("status").asText()).isEqualTo("PUSHED");
        assertThat(res.get("hsId").asText()).startsWith("FAKE-");
        assertThat(res.get("pushError").isNull()).isTrue();
        assertThat(fake.sentPayloads()).hasSize(before + 1);

        Map<String, Object> o = row(id);
        assertThat(o.get("status")).isEqualTo("PUSHED");
        assertThat(o.get("approver_id")).isNotNull();
        assertThat(o.get("approved_at")).isNotNull();
        assertThat(o.get("round_mode")).isEqualTo("HALF");
        assertThat(o.get("box_count")).isEqualTo(10);
        assertThat((java.math.BigDecimal) o.get("total_net_jin")).isEqualByComparingTo("460.5");
        assertThat((java.math.BigDecimal) o.get("avg_jin")).isEqualByComparingTo("46");
        assertThat((java.math.BigDecimal) o.get("std_net_jin")).isEqualByComparingTo("45");
        assertThat((java.math.BigDecimal) o.get("base_price")).isEqualByComparingTo("180");
        assertThat(o.get("rule_id")).isEqualTo(rule);
        assertThat(o.get("rule_name")).isEqualTo("粉蛋李四收购定价方案");
        assertThat((java.math.BigDecimal) o.get("rule_coef")).isEqualByComparingTo("1");
        assertThat((java.math.BigDecimal) o.get("price")).isEqualByComparingTo("181");
        assertThat((java.math.BigDecimal) o.get("amount")).isEqualByComparingTo("1810");
        assertThat(o.get("push_payload")).isEqualTo(fake.sentPayloads().get(before));

        // v7 7.1、6.3：报文字段与备注
        JsonNode p = sentPayload(before);
        assertThat(p.get("code").asText()).isEqualTo(o.get("code"));
        assertThat(p.get("bizDate").asText()).isEqualTo(today.toString());
        assertThat(p.get("redBlueFlagEnum").asText()).isEqualTo("BLUE");
        assertThat(p.get("noteTypeEnum").asText()).isEqualTo("NO_INVOICE");
        assertThat(p.get("custVendorCode").asText()).isEqualTo("GYS0001");
        assertThat(p.get("warehouseCode").asText()).isEqualTo("CK0001");
        assertThat(p.get("comments").asText()).isEqualTo(REMARK_FULL);
        JsonNode line = p.get("detailList").get(0);
        assertThat(p.get("detailList")).hasSize(1);
        assertThat(line.get("productCode").asText()).isEqualTo("SP0001");
        assertThat(line.get("warehouseCode").asText()).isEqualTo("CK0001");
        assertThat(line.get("transQty").asInt()).isEqualTo(10);
        assertThat(line.get("transUomName").asText()).isEqualTo("箱");
        assertThat(line.get("price").decimalValue()).isEqualByComparingTo("181");
        assertThat(line.get("taxPct").decimalValue()).isEqualByComparingTo("0");
        assertThat(line.get("amount").decimalValue()).isEqualByComparingTo("1810");
    }

    @Test
    void changingRulesAfterApprovalDoesNotTouchTheFrozenOrder() {
        long id = submitLiSi();
        post(id, "approve");

        jdbc.update("UPDATE pricing_rule SET coef = 5 WHERE id = ?", rule);
        jdbc.update("UPDATE base_price SET base_price = 999 WHERE spec_id = ?", s360);
        setOption("round.mode", "ceil");

        Map<String, Object> o = row(id);
        assertThat((java.math.BigDecimal) o.get("price")).isEqualByComparingTo("181");
        assertThat((java.math.BigDecimal) o.get("amount")).isEqualByComparingTo("1810");
        assertThat((java.math.BigDecimal) o.get("avg_jin")).isEqualByComparingTo("46");
        assertThat(o.get("round_mode")).isEqualTo("HALF");
    }

    @Test
    void approveNeedsRuleAndBasePrice() {
        setOption("submit.without-price", "allow");
        long noBase = submit(s480, List.of(60.0, 61.0)); // 480 规格当日没录基准价，也没有规则
        var res = call(HttpMethod.POST, "/api/orders/" + noBase + "/approve", null, admin());
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_READY");
        assertThat(row(noBase).get("status")).isEqualTo("PENDING");

        // 补上基准价与规则后同一张单可以审核
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 250)", today, s480);
        long r2 = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, coef, enabled)"
                + " VALUES ('480 规则', 'BUY', " + pink + ", " + s480 + ", 2, 1)");
        assertThat(post(noBase, "approve").get("status").asText()).isEqualTo("PUSHED");
    }

    @Test
    void onlyAdminsCanDriveTheFlow() {
        long id = submitLiSi();
        assertThat(call(HttpMethod.POST, "/api/orders/" + id + "/approve", null, scale()).getStatusCode().value())
                .isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/orders/" + id + "/approve", null, null).getStatusCode().value())
                .isEqualTo(401);
        assertThat(postStatus(999999, "approve")).isEqualTo(400);
        assertThat(row(id).get("status")).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- 回传失败与重传

    @Test
    void rejectedPushLeavesApprovedWithReasonAndRetrySucceeds() throws Exception {
        long id = submitLiSi();
        int before = fake.sentPayloads().size();
        fake.failNext(Failure.REJECT);

        JsonNode res = post(id, "approve");

        assertThat(res.get("status").asText()).isEqualTo("APPROVED");
        assertThat(res.get("pushError").asText()).contains("被拒绝");
        assertThat(res.get("hsId").isNull()).isTrue();
        // 冻结字段已齐全：失败后改规则也不影响
        assertThat((java.math.BigDecimal) row(id).get("amount")).isEqualByComparingTo("1810");
        assertThat(row(id).get("push_error")).isEqualTo(res.get("pushError").asText());

        JsonNode again = post(id, "push");
        assertThat(again.get("status").asText()).isEqualTo("PUSHED");
        assertThat(again.get("pushError").isNull()).isTrue();
        assertThat(row(id).get("push_error")).isNull();
        assertThat(fake.sentPayloads()).hasSize(before + 2);
    }

    @Test
    void noReplyStaysApprovedAndTellsAdminToCheckChanjetFirst() {
        long id = submitLiSi();
        fake.failNext(Failure.NO_REPLY);

        JsonNode res = post(id, "approve");

        assertThat(res.get("status").asText()).isEqualTo("APPROVED");
        assertThat(res.get("pushError").asText()).contains("没有收到好生意的答复").contains("先到好生意").contains("标记为已回传");
        assertThat(row(id).get("hs_id")).isNull();
    }

    @Test
    void retryAfterChangingSettingsSendsExactlyTheSameMessage() {
        long id = submitLiSi();
        int before = fake.sentPayloads().size();
        fake.failNext(Failure.REJECT);
        post(id, "approve");

        // 失败后改系统设置与选项：已存下的报文不重新组装
        setOption("invoice.type", "SPECIAL");
        setOption("invoice.tax-rate", "13");
        setOption("push.remark", "off");
        assertThat(post(id, "push").get("status").asText()).isEqualTo("PUSHED");

        List<String> sent = fake.sentPayloads();
        assertThat(sent).hasSize(before + 2);
        assertThat(sent.get(before + 1)).isEqualTo(sent.get(before));
        assertThat(sent.get(before)).contains("NO_INVOICE").contains("comments");
    }

    @Test
    void manualModeStopsAtApprovedThenPushSucceeds() {
        setOption("push.on-approve", "manual");
        long id = submitLiSi();
        int before = fake.sentPayloads().size();

        JsonNode res = post(id, "approve");

        assertThat(res.get("status").asText()).isEqualTo("APPROVED");
        assertThat(res.get("pushError").isNull()).isTrue(); // 没值就是“待回传”
        assertThat(row(id).get("push_payload")).isNotNull();
        assertThat(fake.sentPayloads()).hasSize(before);

        assertThat(post(id, "push").get("status").asText()).isEqualTo("PUSHED");
        assertThat(fake.sentPayloads()).hasSize(before + 1);
        // 已回传的单再点回传：直接返回当前状态，不再发
        assertThat(post(id, "push").get("status").asText()).isEqualTo("PUSHED");
        assertThat(fake.sentPayloads()).hasSize(before + 1);
    }

    @Test
    void pushingAnUnapprovedOrderIsRefused() {
        long id = submitLiSi();
        assertThat(postStatus(id, "push")).isEqualTo(409);
        assertThat(postStatus(id, "mark-pushed")).isEqualTo(409);
        assertThat(postStatus(id, "unapprove") == 200).as("待审核再退回：直接返回当前状态").isTrue();
        assertThat(row(id).get("status")).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- 退回、标记、作废

    @Test
    void unapproveClearsEverythingAndNewSettingsTakeEffectOnReapprove() throws Exception {
        long id = submitLiSi();
        int before = fake.sentPayloads().size();
        fake.failNext(Failure.NO_REPLY);
        post(id, "approve");

        JsonNode res = post(id, "unapprove");

        assertThat(res.get("status").asText()).isEqualTo("PENDING");
        Map<String, Object> o = row(id);
        for (String col : List.of("approver_id", "approved_at", "hs_id", "push_error", "push_payload", "round_mode",
                "box_count", "total_net_jin", "avg_jin", "std_net_jin", "base_price", "rule_name", "rule_coef",
                "rule_loss_jin", "price", "amount")) {
            assertThat(o.get(col)).as(col).isNull();
        }
        // 规则是建单时选的，退回不清（附录 E R-1 行）
        assertThat(o.get("rule_id")).isNotNull();
        // 流水还在，待审核可以改
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weigh_record WHERE order_id = ?", Integer.class, id))
                .isEqualTo(10);

        // 要让新的设置生效就走退回：重新审核按当时的设置组装
        setOption("invoice.tax-rate", "13");
        assertThat(post(id, "approve").get("status").asText()).isEqualTo("PUSHED");
        assertThat(sentPayload(before + 1).get("detailList").get(0).get("taxPct").decimalValue())
                .isEqualByComparingTo("13");
    }

    @Test
    void pushedOrdersCannotBeUnapproved() {
        long id = submitLiSi();
        post(id, "approve");
        assertThat(postStatus(id, "unapprove")).isEqualTo(409);
        assertThat(postStatus(id, "void")).isEqualTo(409);
        assertThat(row(id).get("status")).isEqualTo("PUSHED");
    }

    @Test
    void markPushedAfterNoReplyKeepsGivenChanjetId() {
        long id = submitLiSi();
        int before = fake.sentPayloads().size();
        fake.failNext(Failure.NO_REPLY);
        post(id, "approve");

        JsonNode res = call(HttpMethod.POST, "/api/orders/" + id + "/mark-pushed", Map.of("hsId", " HS-20261116-001 "),
                admin()).getBody();

        assertThat(res.get("status").asText()).isEqualTo("PUSHED");
        assertThat(res.get("hsId").asText()).isEqualTo("HS-20261116-001");
        assertThat(row(id).get("push_error")).isNull();
        assertThat(fake.sentPayloads()).hasSize(before + 1); // 标记不会再发
    }

    @Test
    void markPushedWithoutAnIdAlsoWorks() {
        setOption("push.on-approve", "manual");
        long id = submitLiSi();
        post(id, "approve");

        // 不带请求体
        JsonNode res = post(id, "mark-pushed");
        assertThat(res.get("status").asText()).isEqualTo("PUSHED");
        assertThat(res.get("hsId").isNull()).isTrue();
    }

    @Test
    void voidedOrdersStayVoid() {
        long id = submitLiSi();
        JsonNode res = post(id, "void");
        assertThat(res.get("status").asText()).isEqualTo("VOID");
        assertThat(row(id).get("status")).isEqualTo("VOID");

        // 作废后不能审核、回传、退回、标记；批次号不再复用（行还在）
        assertThat(postStatus(id, "approve")).isEqualTo(409);
        assertThat(postStatus(id, "push")).isEqualTo(409);
        assertThat(postStatus(id, "unapprove")).isEqualTo(409);
        assertThat(postStatus(id, "mark-pushed")).isEqualTo(409);
        assertThat(post(id, "void").get("status").asText()).isEqualTo("VOID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void approvedOrdersMustBeSentBackBeforeVoiding() {
        setOption("push.on-approve", "manual");
        long id = submitLiSi();
        post(id, "approve");
        assertThat(postStatus(id, "void")).isEqualTo(409);
        post(id, "unapprove");
        assertThat(post(id, "void").get("status").asText()).isEqualTo("VOID");
    }

    // ---------------------------------------------------------------- 并发

    @Test
    void twoApprovalsAtTheSameTimeReachTheFakeOnlyOnce() throws Exception {
        long id = submitLiSi();
        int before = fake.sentPayloads().size();
        int n = 6;
        var pool = Executors.newFixedThreadPool(n);
        var gate = new CyclicBarrier(n);
        try {
            List<Callable<JsonNode>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                jobs.add(() -> {
                    gate.await();
                    return post(id, "approve");
                });
            }
            for (Future<JsonNode> f : pool.invokeAll(jobs)) {
                // 后到的等到锁后看到已不是待审核，直接返回当前状态
                assertThat(f.get().get("status").asText()).isEqualTo("PUSHED");
            }
        } finally {
            pool.shutdown();
        }
        assertThat(fake.sentPayloads()).hasSize(before + 1);
    }

    // ---------------------------------------------------------------- push.remark 与长度上限

    @Test
    void remarkOptionDecidesWhatIsSent() throws Exception {
        int before = fake.sentPayloads().size();

        // full（默认）
        post(submitLiSi(), "approve");
        assertThat(sentPayload(before).get("comments").asText()).isEqualTo(REMARK_FULL);

        // summary：只带共几箱与归类
        setOption("push.remark", "summary");
        post(submitLiSi(), "approve");
        assertThat(sentPayload(before + 1).get("comments").asText()).isEqualTo(REMARK_SUMMARY);

        // off：报文里没有备注字段
        setOption("push.remark", "off");
        post(submitLiSi(), "approve");
        assertThat(sentPayload(before + 2).has("comments")).isFalse();
    }

    @Test
    void remarkTooLongFallsBackToSummary() throws Exception {
        int before = fake.sentPayloads().size();
        // 全文 97 个字：上限 96 降为汇总，97 仍带全文
        setOption("push.remark-max-length", "96");
        post(submitLiSi(), "approve");
        assertThat(sentPayload(before).get("comments").asText()).isEqualTo(REMARK_SUMMARY);

        setOption("push.remark-max-length", "97");
        post(submitLiSi(), "approve");
        assertThat(sentPayload(before + 1).get("comments").asText()).isEqualTo(REMARK_FULL);
    }

    // ---------------------------------------------------------------- 失败开关接口

    @Test
    void failSwitchEndpointArmsTheFakeOnce() {
        long id = submitLiSi();
        var armed = call(HttpMethod.POST, "/api/dev/chanjet/fail-next", Map.of("mode", "reject"), admin());
        assertThat(armed.getStatusCode().value()).isEqualTo(200);
        assertThat(fake.pendingFailure()).isEqualTo(Failure.REJECT);

        assertThat(post(id, "approve").get("status").asText()).isEqualTo("APPROVED");
        assertThat(fake.pendingFailure()).isNull(); // 用一次就复位
        assertThat(post(id, "push").get("status").asText()).isEqualTo("PUSHED");

        call(HttpMethod.POST, "/api/dev/chanjet/fail-next", Map.of("mode", "no-reply"), admin());
        assertThat(fake.pendingFailure()).isEqualTo(Failure.NO_REPLY);
        call(HttpMethod.POST, "/api/dev/chanjet/fail-next", Map.of("mode", "none"), admin());
        assertThat(fake.pendingFailure()).isNull();

        assertThat(call(HttpMethod.POST, "/api/dev/chanjet/fail-next", Map.of("mode", "boom"), admin())
                .getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/api/dev/chanjet/fail-next", Map.of("mode", "reject"), scale())
                .getStatusCode().value()).isEqualTo(403);
    }
}

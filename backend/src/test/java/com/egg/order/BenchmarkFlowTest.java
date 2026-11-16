package com.egg.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.egg.MySqlTestConfig;
import com.egg.chanjet.ChanjetClient;
import com.egg.chanjet.FakeChanjetClient;
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

/**
 * P2-5 验收：基准算例（李四 10 箱粉蛋，v7 第 6 章）全流程。只走对外接口，不碰数据库里的业务表：
 * 档案从模拟好生意同步，规格、皮重、基准价、规则由管理员接口录入；磅秤员按 v7 6.2 查农户、取批次号、预览、提交；
 * 管理员按 v7 6.3 核对、审核，断言模拟好生意收到的报文与 v7 6.3 一致。另覆盖 6.5 三种取整、6.6 手工录入、6.7 合并称重，
 * 以及 P2-4 留下的“退回后可修改、作废后不可修改”。
 *
 * 只在模拟好生意上验证，未经实物验证：没有接触过真实的好生意账套，报文字段名与日期格式仍属 H-3。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.admin.phone=13700000051", "egg.admin.password=boss-pass-5", "egg.admin.name=基准算例管理员"})
@Import(MySqlTestConfig.class)
class BenchmarkFlowTest {

    /** v7 6.2 第 4 步：各箱净重 */
    private static final List<Double> LI_SI_BOXES = List.of(46.5, 45.8, 46.0, 46.0, 46.0, 45.6, 45.6, 45.6, 45.6, 47.8);
    /** v7 6.3 备注栏 */
    private static final String REMARK_FULL = "共10箱；逐箱：46.5,45.8,46,46,46,45.6,45.6,45.6,45.6,47.8；"
            + "归类：45.6kg-4箱,46kg-3箱,46.5kg-1箱,45.8kg-1箱,47.8kg-1箱";

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

    private final LocalDate today = LocalDate.now();
    private FakeChanjetClient fake;
    private int sentBefore;
    private long pinkId, spec360, warehouseId, liSiId, wangId, ruleId;

    /** 每个用例从空库开始，按 v7 6.1 的初始条件用对外接口重新录一遍。 */
    @BeforeEach
    void initialConditions() {
        for (String t : List.of("weigh_record", "purchase_order", "batch_seq", "pricing_rule_bind", "pricing_rule",
                "base_price", "vendor_phone", "vendor", "warehouse", "product", "customer", "spec", "tare_box",
                "`option`")) {
            jdbc.update("DELETE FROM " + t);
        }
        fake = (FakeChanjetClient) chanjet;
        fake.failNext(null);
        sentBefore = fake.sentPayloads().size();

        // 好生意档案：模拟好生意里有粉蛋、李四（尾号 1234）与王秀英（尾号 1234）、1 号仓库
        assertThat(admin(HttpMethod.POST, "/api/archive/sync", null).get("vendors").asInt()).isGreaterThanOrEqualTo(2);
        pinkId = archiveId("products", "粉蛋");
        warehouseId = archiveId("warehouses", "1号仓库");

        spec360 = admin(HttpMethod.POST, "/api/specs", Map.of("name", "360枚/箱", "eggCount", 360, "stdNetKg", 45)).get("id")
                .asLong();
        admin(HttpMethod.POST, "/api/tares", Map.of("name", "2号包装箱", "tareKg", 2, "hotkey", true));
        admin(HttpMethod.PUT, "/api/base-prices", Map.of("date", today.toString(), "specId", spec360, "price", 180));

        JsonNode farmers = scale(HttpMethod.GET, "/api/farmers?tail=1234", null);
        liSiId = farmerId(farmers, "李四");
        wangId = farmerId(farmers, "王秀英");
        ruleId = admin(HttpMethod.POST, "/api/rules", Map.of("name", "粉蛋李四收购定价方案", "kind", "BUY", "productId", pinkId,
                "specId", spec360, "coef", 1, "enabled", true, "bindIds", List.of(liSiId))).get("id").asLong();
    }

    // ---------------------------------------------------------------- 工具

    private String login(String phone, String password, String client) {
        return http.postForObject("/api/auth/login", Map.of("phone", phone, "password", password, "client", client),
                JsonNode.class).get("token").asText();
    }

    private String adminToken() {
        if (adminToken == null) {
            adminToken = login("13700000051", "boss-pass-5", "WEB");
        }
        return adminToken;
    }

    private String scaleToken() {
        if (scaleToken == null) {
            JsonNode created = call(HttpMethod.POST, "/api/accounts", Map.of("name", "基准算例磅秤员", "phone", "13700000052"),
                    adminToken()).getBody();
            scaleToken = login("13700000052", created.get("password").asText(), "APP");
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

    /** 成功的调用返回正文；失败时把正文带进断言信息。 */
    private JsonNode ok(ResponseEntity<JsonNode> res) {
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return res.getBody();
    }

    private JsonNode admin(HttpMethod m, String url, Object body) {
        return ok(call(m, url, body, adminToken()));
    }

    private JsonNode scale(HttpMethod m, String url, Object body) {
        return ok(call(m, url, body, scaleToken()));
    }

    private long archiveId(String type, String name) {
        JsonNode page = admin(HttpMethod.GET, "/api/archive/" + type + "?q=" + name, null);
        return StreamSupport.stream(page.get("items").spliterator(), false)
                .filter(r -> name.equals(r.get("name").asText())).findFirst().orElseThrow().get("id").asLong();
    }

    private long farmerId(JsonNode farmers, String name) {
        return StreamSupport.stream(farmers.spliterator(), false).filter(f -> name.equals(f.get("name").asText()))
                .findFirst().orElseThrow().get("id").asLong();
    }

    private Map<String, Object> box(int seq, double net) {
        Map<String, Object> m = new HashMap<>();
        m.put("seq", seq);
        m.put("netKg", net);
        m.put("grossKg", net + 2);
        m.put("tareKg", 2);
        m.put("tareName", "2号包装箱");
        m.put("groupNo", seq);
        m.put("groupK", 1);
        m.put("manual", false);
        m.put("printed", true); // v7 6.2：逐箱打印，称一箱打一张
        return m;
    }

    private List<Map<String, Object>> boxes(List<Double> nets) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < nets.size(); i++) {
            out.add(box(i + 1, nets.get(i)));
        }
        return out;
    }

    private JsonNode quote(List<Double> nets) {
        return scale(HttpMethod.POST, "/api/orders/quote",
                Map.of("vendorId", liSiId, "productId", pinkId, "specId", spec360, "boxNetKg", nets));
    }

    /** 磅秤员取批次号并提交，返回提交结果。 */
    private JsonNode submit(List<Map<String, Object>> boxes) {
        String batchNo = scale(HttpMethod.POST, "/api/batches", null).get("batchNo").asText();
        return scale(HttpMethod.POST, "/api/orders", Map.of("batchNo", batchNo, "vendorId", liSiId, "productId", pinkId,
                "specId", spec360, "warehouseId", warehouseId, "boxes", boxes));
    }

    private JsonNode lookup(String code, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return ok(http.exchange("/api/boxes/lookup?code={c}", HttpMethod.GET, new HttpEntity<>(null, h), JsonNode.class,
                code));
    }

    private JsonNode detail(long id) {
        return admin(HttpMethod.GET, "/api/orders/" + id, null);
    }

    private JsonNode post(long id, String action) {
        return admin(HttpMethod.POST, "/api/orders/" + id + "/" + action, null);
    }

    private void assertNum(JsonNode n, String expected) {
        assertThat(n.isNull()).as("应为 " + expected + " 实为 null").isFalse();
        assertThat(n.decimalValue()).isEqualByComparingTo(expected);
    }

    // ---------------------------------------------------------------- v7 6.2 – 6.4：李四 10 箱

    @Test
    void liSiTenBoxesFromSubmitToChanjet() throws Exception {
        // 6.2 第 1 步：尾号 1234 匹配到李四与王秀英，选李四；商品、规格、规则、基准价都带得出来
        JsonNode farmers = scale(HttpMethod.GET, "/api/farmers?tail=1234", null);
        assertThat(farmers).hasSize(2);
        assertThat(StreamSupport.stream(farmers.spliterator(), false).map(f -> f.get("name").asText()).toList())
                .containsExactlyInAnyOrder("李四", "王秀英");
        JsonNode liSi = StreamSupport.stream(farmers.spliterator(), false).filter(f -> f.get("id").asLong() == liSiId)
                .findFirst().orElseThrow();
        assertThat(liSi.get("matchedPhones").get(0).asText()).isEqualTo("13603511234");
        JsonNode rules = scale(HttpMethod.GET, "/api/farmers/" + liSiId + "/rules", null);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("name").asText()).isEqualTo("粉蛋李四收购定价方案");
        assertThat(scale(HttpMethod.GET, "/api/farmers/" + wangId + "/rules", null)).isEmpty(); // 王秀英没有绑定规则

        JsonNode boot = scale(HttpMethod.GET, "/api/app/bootstrap", null);
        assertThat(boot.get("products").get(0).get("name").asText()).isEqualTo("粉蛋");
        JsonNode spec = boot.get("specs").get(0);
        assertThat(spec.get("name").asText()).isEqualTo("360枚/箱");
        assertNum(spec.get("stdNetKg"), "45");
        assertNum(spec.get("basePrice"), "180");
        assertThat(boot.get("warehouses").get(0).get("name").asText()).isEqualTo("1号仓库");
        assertThat(boot.get("tares").get(0).get("name").asText()).isEqualTo("2号包装箱");
        assertNum(boot.get("tares").get(0).get("tareKg"), "2");
        // 6.1：系统设置均为默认值（四舍五入、逐箱打印、精简）
        assertThat(boot.get("settings").get("round.mode").asText()).isEqualTo("half");
        assertThat(boot.get("settings").get("print.timing").asText()).isEqualTo("per-box");
        assertThat(boot.get("settings").get("label.content").asText()).isEqualTo("minimal");

        // 6.2 第 3 步：第 1 箱 46.5kg，实时均重 47（原始 46.50）
        JsonNode first = quote(List.of(46.5));
        assertNum(first.get("rawAvgKg"), "46.5");
        assertNum(first.get("avgKg"), "47");
        // 第 5、6 步：10 箱 460.5kg，均重 46（原始 46.05），181 元/箱，1810 元
        JsonNode q = quote(LI_SI_BOXES);
        assertThat(q.get("boxCount").asInt()).isEqualTo(10);
        assertNum(q.get("totalKg"), "460.5");
        assertNum(q.get("rawAvgKg"), "46.05");
        assertNum(q.get("avgKg"), "46");
        assertNum(q.get("tailKg"), "1");
        assertNum(q.get("priceDiff"), "1");
        assertNum(q.get("basePrice"), "180");
        assertNum(q.get("unitPrice"), "181");
        assertNum(q.get("amount"), "1810");
        assertThat(q.get("issues")).isEmpty();

        // 第 7 步：提交单据与 10 条流水；重试复用同一个批次号只会得到同一张单
        String batchNo = scale(HttpMethod.POST, "/api/batches", null).get("batchNo").asText();
        Map<String, Object> form = Map.of("batchNo", batchNo, "vendorId", liSiId, "productId", pinkId, "specId", spec360,
                "warehouseId", warehouseId, "boxes", boxes(LI_SI_BOXES));
        JsonNode submitted = scale(HttpMethod.POST, "/api/orders", form);
        assertThat(scale(HttpMethod.POST, "/api/orders", form).get("id").asLong()).isEqualTo(submitted.get("id").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Integer.class)).isEqualTo(1);
        assertThat(submitted.get("status").asText()).isEqualTo("PENDING");
        assertThat(submitted.get("source").asText()).isEqualTo("APP");
        assertThat(submitted.get("boxCount").asInt()).isEqualTo(10);
        assertNum(submitted.get("totalNetKg"), "460.5");
        assertNum(submitted.get("avgKg"), "46");
        assertNum(submitted.get("submittedPrice"), "181");
        assertNum(submitted.get("submittedAmount"), "1810");
        long id = submitted.get("id").asLong();
        String code = submitted.get("code").asText();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weigh_record WHERE order_id = ?", Integer.class, id))
                .isEqualTo(10);

        // 管理员在 Web 上核对：导航提示、列表、详情、逐箱流水、箱码查询
        JsonNode counts = admin(HttpMethod.GET, "/api/orders/counts", null);
        assertThat(counts.get("pending").asInt()).isEqualTo(1);
        assertThat(counts.get("failed").asInt()).isZero();
        assertThat(counts.get("missingBasePrices")).isEmpty();
        JsonNode row = admin(HttpMethod.GET, "/api/orders?tab=pending", null).get("items").get(0);
        assertThat(row.get("id").asLong()).isEqualTo(id);
        assertThat(row.get("vendorName").asText()).isEqualTo("李四");
        assertThat(row.get("boxCount").asInt()).isEqualTo(10);
        assertNum(row.get("amount"), "1810");
        assertThat(row.get("amountChanged").asBoolean()).isFalse();

        JsonNode d = detail(id);
        assertThat(d.get("status").asText()).isEqualTo("PENDING");
        assertThat(d.get("frozen").asBoolean()).isFalse();
        assertThat(d.get("vendor").get("name").asText()).isEqualTo("李四");
        assertThat(d.get("warehouse").get("name").asText()).isEqualTo("1号仓库");
        assertThat(d.get("product").get("name").asText()).isEqualTo("粉蛋");
        assertThat(d.get("spec").get("name").asText()).isEqualTo("360枚/箱");
        assertThat(d.get("submittedDiffers").asBoolean()).isFalse();
        assertThat(d.get("manualCount").asInt()).isZero();
        assertNum(d.get("pricing").get("unitPrice"), "181");
        assertNum(d.get("pricing").get("amount"), "1810");
        assertThat(d.get("pricing").get("ruleName").asText()).isEqualTo("粉蛋李四收购定价方案");

        JsonNode records = admin(HttpMethod.GET, "/api/orders/" + id + "/records", null);
        assertThat(records.get("editable").asBoolean()).isTrue();
        assertThat(records.get("boxes")).hasSize(10);
        assertNum(records.get("boxes").get(9).get("netKg"), "47.8");
        // 6.3：归类按箱数多的在前，箱数相同的按首次出现的先后
        List<String> groups = new ArrayList<>();
        records.get("groups").forEach(g -> groups.add(g.get("kg").decimalValue().toPlainString() + "kg-" + g.get("boxes").asInt() + "箱"));
        assertThat(groups).containsExactly("45.6kg-4箱", "46kg-3箱", "46.5kg-1箱", "45.8kg-1箱", "47.8kg-1箱");

        JsonNode hit = lookup(batchNo + "#3", scaleToken());
        assertThat(hit.get("seq").asInt()).isEqualTo(3);
        assertNum(hit.get("netKg"), "46");
        assertThat(hit.get("vendor").get("name").asText()).isEqualTo("李四");
        assertThat(hit.get("status").asText()).isEqualTo("PENDING");
        assertNum(hit.get("avgKg"), "46");
        assertNum(hit.get("price"), "181");

        // 审核通过：默认自动回传，模拟好生意只收到一份报文
        assertThat(sent()).isEmpty();
        JsonNode approved = post(id, "approve");
        assertThat(approved.get("status").asText()).isEqualTo("PUSHED");
        assertThat(approved.get("hsId").asText()).startsWith("FAKE-");
        assertThat(approved.get("pushError").isNull()).isTrue();
        assertNum(approved.get("price"), "181");
        assertNum(approved.get("amount"), "1810");
        assertThat(sent()).hasSize(1);

        // 6.3：好生意采购入库单预期结果。字段名与取值逐项核对，且不多带字段
        JsonNode p = json.readTree(sent().get(0));
        assertThat(fieldNames(p)).containsExactlyInAnyOrder("bizDate", "code", "redBlueFlagEnum", "noteTypeEnum",
                "custVendorCode", "warehouseCode", "comments", "detailList");
        assertThat(p.get("code").asText()).isEqualTo(code);
        assertThat(p.get("bizDate").asText()).isEqualTo(today.toString());
        assertThat(p.get("redBlueFlagEnum").asText()).isEqualTo("BLUE");
        assertThat(p.get("noteTypeEnum").asText()).isEqualTo("NO_INVOICE");
        assertThat(p.get("custVendorCode").asText()).isEqualTo("GYS0001"); // 供应商：李四
        assertThat(p.get("warehouseCode").asText()).isEqualTo("CK01"); // 仓库：所选的 1号仓库
        assertThat(p.get("comments").asText()).isEqualTo(REMARK_FULL); // 备注栏
        assertThat(p.get("detailList")).hasSize(1); // 商品明细行：粉蛋 ｜ 360枚/箱 ｜ 10箱 ｜ 181元/箱 ｜ 1810元
        JsonNode line = p.get("detailList").get(0);
        assertThat(fieldNames(line)).containsExactlyInAnyOrder("productCode", "warehouseCode", "transQty", "transUomName",
                "price", "taxPct", "amount");
        assertThat(line.get("productCode").asText()).isEqualTo("SP0001");
        assertThat(line.get("warehouseCode").asText()).isEqualTo("CK01");
        assertNum(line.get("transQty"), "10");
        assertThat(line.get("transUomName").asText()).isEqualTo("箱");
        assertNum(line.get("price"), "181");
        assertNum(line.get("taxPct"), "0");
        assertNum(line.get("amount"), "1810");

        // 审核后：详情读冻结值，回传的原文与存下的一致；列表与计数跟着变；箱码查询读冻结值
        JsonNode after = detail(id);
        assertThat(after.get("status").asText()).isEqualTo("PUSHED");
        assertThat(after.get("frozen").asBoolean()).isTrue();
        assertNum(after.get("pricing").get("amount"), "1810");
        assertThat(jdbc.queryForObject("SELECT push_payload FROM purchase_order WHERE id = ?", String.class, id))
                .isEqualTo(sent().get(0));
        assertThat(admin(HttpMethod.GET, "/api/orders/counts", null).get("pending").asInt()).isZero();
        assertThat(admin(HttpMethod.GET, "/api/orders?tab=sent", null).get("items")).hasSize(1);
        assertThat(admin(HttpMethod.GET, "/api/orders?tab=pending", null).get("items")).isEmpty();
        assertThat(lookup(batchNo + "#3", adminToken())
                .get("status").asText()).isEqualTo("PUSHED");
    }

    /** 本用例里模拟好生意收到的报文（模拟好生意是全进程共用的，前面的用例发过的不算）。 */
    private List<String> sent() {
        List<String> all = fake.sentPayloads();
        return all.subList(Math.min(sentBefore, all.size()), all.size());
    }

    private List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ---------------------------------------------------------------- v7 6.5：均重取整规则对照

    @Test
    void roundingRulesMatchTheComparisonTable() {
        // 四舍五入、抹零：46kg、181 元、1810 元；进位：47kg、182 元、1820 元
        Map<String, String[]> expected = Map.of(
                "half", new String[] {"46", "1", "181", "1810"},
                "floor", new String[] {"46", "1", "181", "1810"},
                "ceil", new String[] {"47", "2", "182", "1820"});
        for (var e : expected.entrySet()) {
            admin(HttpMethod.PUT, "/api/settings", Map.of("values", Map.of("round.mode", e.getKey())));
            JsonNode q = quote(LI_SI_BOXES);
            String[] want = e.getValue();
            assertNum(q.get("rawAvgKg"), "46.05");
            assertNum(q.get("avgKg"), want[0]);
            assertNum(q.get("tailKg"), want[1]);
            assertNum(q.get("unitPrice"), want[2]);
            assertNum(q.get("amount"), want[3]);
        }
    }

    // ---------------------------------------------------------------- v7 6.6：手工录入

    @Test
    void manualEntryOnBoxTenGives1820() throws Exception {
        // 磅秤员对第 10 箱手工录入，47.8 改为 52.8：原始均重 46.55，实时均重 47
        List<Double> edited = new ArrayList<>(LI_SI_BOXES);
        edited.set(9, 52.8);
        JsonNode q = quote(edited);
        assertNum(q.get("totalKg"), "465.5");
        assertNum(q.get("rawAvgKg"), "46.55");
        assertNum(q.get("avgKg"), "47");
        assertNum(q.get("unitPrice"), "182");
        assertNum(q.get("amount"), "1820");

        List<Map<String, Object>> list = boxes(edited);
        list.get(9).put("manual", true);
        list.get(9).put("rawKg", 47.8);
        JsonNode submitted = submit(list);
        assertNum(submitted.get("submittedPrice"), "182");
        assertNum(submitted.get("submittedAmount"), "1820");
        long id = submitted.get("id").asLong();

        // 第 10 箱流水：净重 52.8kg，带手录记号，秤原始读数 47.8kg
        JsonNode records = admin(HttpMethod.GET, "/api/orders/" + id + "/records", null);
        JsonNode tenth = records.get("boxes").get(9);
        assertNum(tenth.get("netKg"), "52.8");
        assertThat(tenth.get("manual").asBoolean()).isTrue();
        assertNum(tenth.get("rawKg"), "47.8");
        assertThat(records.get("manualCount").asInt()).isEqualTo(1);
        assertThat(detail(id).get("manualCount").asInt()).isEqualTo(1);

        JsonNode approved = post(id, "approve");
        assertThat(approved.get("status").asText()).isEqualTo("PUSHED");
        assertNum(approved.get("price"), "182");
        assertNum(approved.get("amount"), "1820");
        JsonNode line = json.readTree(sent().get(0)).get("detailList").get(0);
        assertNum(line.get("price"), "182");
        assertNum(line.get("amount"), "1820");
        assertThat(json.readTree(sent().get(0)).get("comments").asText())
                .startsWith("共10箱；逐箱：46.5,45.8,46,46,46,45.6,45.6,45.6,45.6,52.8；");
    }

    @Test
    void webEditOfBoxTenAfterSubmitGives1820AndShowsTheDifference() throws Exception {
        long id = submit(boxes(LI_SI_BOXES)).get("id").asLong();
        assertNum(detail(id).get("pricing").get("amount"), "1810");

        // Web 整单覆盖：第 10 箱改为 52.8，前端标手录并记下原读数 47.8
        List<Map<String, Object>> list = boxes(LI_SI_BOXES);
        list.get(9).put("netKg", 52.8);
        list.get(9).put("manual", true);
        list.get(9).put("rawKg", 47.8);
        JsonNode d = admin(HttpMethod.PUT, "/api/orders/" + id, Map.of("bizDate", today.toString(), "vendorId", liSiId,
                "productId", pinkId, "specId", spec360, "warehouseId", warehouseId, "boxes", list));
        assertNum(d.get("pricing").get("avgKg"), "47");
        assertNum(d.get("pricing").get("unitPrice"), "182");
        assertNum(d.get("pricing").get("amount"), "1820");
        // 与 APP 提交时的金额对比：提交时是 1810，现在 1820
        assertNum(d.get("submittedAmount"), "1810");
        assertThat(d.get("submittedDiffers").asBoolean()).isTrue();
        assertThat(d.get("manualCount").asInt()).isEqualTo(1);
        JsonNode tenth = admin(HttpMethod.GET, "/api/orders/" + id + "/records", null).get("boxes").get(9);
        assertThat(tenth.get("manual").asBoolean()).isTrue();
        assertNum(tenth.get("rawKg"), "47.8");

        assertNum(post(id, "approve").get("amount"), "1820");
        assertNum(json.readTree(sent().get(0)).get("detailList").get(0).get("amount"), "1820");
    }

    // ---------------------------------------------------------------- v7 6.7：合并称重

    @Test
    void mergedWeighingKeepsTheGroup() {
        // 一次放上 3 箱，每箱净重 46kg：识别为 3 箱，生成 3 条流水，共用一个称重组号
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> b = box(i, 46.0);
            b.put("groupNo", 1);
            b.put("groupK", 3);
            list.add(b);
        }
        long id = submit(list).get("id").asLong();

        JsonNode records = admin(HttpMethod.GET, "/api/orders/" + id + "/records", null);
        assertThat(records.get("boxes")).hasSize(3);
        records.get("boxes").forEach(b -> {
            assertThat(b.get("groupNo").asInt()).isEqualTo(1);
            assertThat(b.get("groupK").asInt()).isEqualTo(3);
            assertNum(b.get("netKg"), "46");
        });
        assertThat(records.get("groups")).hasSize(1);
        assertThat(records.get("groups").get(0).get("boxes").asInt()).isEqualTo(3);
        assertNum(detail(id).get("pricing").get("totalKg"), "138");
    }

    // ---------------------------------------------------------------- P2-4 留下的：退回后可修改，作废后不可修改

    @Test
    void unapprovedOrderCanBeEditedAndReapprovedButVoidOrderCannot() throws Exception {
        long id = submit(boxes(LI_SI_BOXES)).get("id").asLong();
        post(id, "approve");
        assertThat(sent()).hasSize(1);

        // 已回传的单不能直接改，也不能退回
        List<Double> edited = new ArrayList<>(LI_SI_BOXES);
        edited.set(9, 52.8);
        Map<String, Object> form = new HashMap<>(Map.of("bizDate", today.toString(), "vendorId", liSiId, "productId",
                pinkId, "specId", spec360, "warehouseId", warehouseId, "boxes", boxes(edited)));
        var locked = call(HttpMethod.PUT, "/api/orders/" + id, form, adminToken());
        assertThat(locked.getStatusCode().value()).isEqualTo(409);
        assertThat(locked.getBody().get("code").asText()).isEqualTo("ORDER_LOCKED");

        // 换一张：先让回传失败停在已审核，再退回 → 可以修改 → 再审核按修改后的内容回传
        long second = submit(boxes(LI_SI_BOXES)).get("id").asLong();
        fake.failNext(FakeChanjetClient.Failure.REJECT);
        assertThat(post(second, "approve").get("status").asText()).isEqualTo("APPROVED");
        var lockedApproved = call(HttpMethod.PUT, "/api/orders/" + second, form, adminToken());
        assertThat(lockedApproved.getStatusCode().value()).isEqualTo(409);

        assertThat(post(second, "unapprove").get("status").asText()).isEqualTo("PENDING");
        JsonNode d = admin(HttpMethod.PUT, "/api/orders/" + second, form);
        assertNum(d.get("pricing").get("amount"), "1820");
        JsonNode again = post(second, "approve");
        assertThat(again.get("status").asText()).isEqualTo("PUSHED");
        assertNum(again.get("amount"), "1820");
        int count = sent().size(); // 第一张 1 份 + 第二张被拒绝的 1 份 + 重新审核的 1 份
        assertThat(count).isEqualTo(3);
        assertNum(json.readTree(sent().get(count - 1)).get("detailList").get(0).get("amount"), "1820");

        // 作废后：不能修改、不能审核
        long third = submit(boxes(LI_SI_BOXES)).get("id").asLong();
        assertThat(post(third, "void").get("status").asText()).isEqualTo("VOID");
        var voided = call(HttpMethod.PUT, "/api/orders/" + third, form, adminToken());
        assertThat(voided.getStatusCode().value()).isEqualTo(409);
        assertThat(voided.getBody().get("code").asText()).isEqualTo("ORDER_LOCKED");
        assertThat(call(HttpMethod.POST, "/api/orders/" + third + "/approve", null, adminToken()).getStatusCode().value())
                .isEqualTo(409);
        assertThat(sent()).hasSize(count);
    }
}

package com.egg.pricing;

import static org.assertj.core.api.Assertions.assertThat;

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
 * P1-5 验收（按附录 E R-1 行改版）：没填名称被拒；规则不绑农户或客户，名称重复返回 RULE_CONFLICT 并指出同名规则；
 * 规则打包规格、包装箱（皮重）与每箱损耗调整（可正可负）。另外覆盖修改、筛选，以及管理员与磅秤员都能调（开发规格 5.2：A+S）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.admin.phone=13700000001", "egg.admin.password=boss-pass-1", "egg.admin.name=规则测试管理员"})
@Import(MySqlTestConfig.class)
class RuleApiTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;

    private static volatile String adminToken;
    private static volatile String scaleToken;

    private long pink, red, s360, s240, box2, box1;

    @BeforeEach
    void fixtures() {
        for (String t : List.of("pricing_rule", "tare_box", "product", "spec")) {
            jdbc.update("DELETE FROM " + t);
        }
        pink = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0001', '粉蛋', '箱')");
        red = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0002', '红蛋', '箱')");
        s360 = insert("INSERT INTO spec (name, egg_count, std_net_jin, enabled) VALUES ('360枚/箱', 360, 45, 1)");
        s240 = insert("INSERT INTO spec (name, egg_count, std_net_jin, enabled) VALUES ('240枚/箱', 240, 30, 1)");
        box2 = insert("INSERT INTO tare_box (name, tare_jin, hotkey, enabled) VALUES ('2号包装箱', 4, 1, 1)");
        box1 = insert("INSERT INTO tare_box (name, tare_jin, hotkey, enabled) VALUES ('1号包装箱', 3, 1, 1)");
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
            adminToken = token("13700000001", "boss-pass-1", "WEB");
        }
        return adminToken;
    }

    private String scale() {
        if (scaleToken == null) {
            JsonNode created = call(HttpMethod.POST, "/api/accounts", Map.of("name", "测试磅秤员", "phone", "13700000002"), admin())
                    .getBody();
            scaleToken = token("13700000002", created.get("password").asText(), "APP");
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

    private ResponseEntity<JsonNode> post(String url, Object body) {
        return call(HttpMethod.POST, url, body, admin());
    }

    private ResponseEntity<JsonNode> put(String url, Object body) {
        return call(HttpMethod.PUT, url, body, admin());
    }

    private JsonNode get(String url) {
        return call(HttpMethod.GET, url, null, admin()).getBody();
    }

    /** 默认是粉蛋、360枚/箱、2号包装箱、损耗 0、系数 0.5 的收购规则。 */
    private Map<String, Object> rule(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("kind", "BUY");
        m.put("productId", pink);
        m.put("specId", s360);
        m.put("tareBoxId", box2);
        m.put("coef", 0.5);
        return m;
    }

    private JsonNode created(Map<String, Object> body) {
        var res = post("/api/rules", body);
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(200);
        return res.getBody();
    }

    private void assertInvalid(ResponseEntity<JsonNode> res, String messagePart) {
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID");
        assertThat(res.getBody().get("message").asText()).contains(messagePart);
    }

    // ---------------- 新建与校验 ----------------

    @Test
    void ruleWithoutNameIsRejected() {
        Map<String, Object> noName = rule("x");
        noName.remove("name");
        assertInvalid(post("/api/rules", noName), "名称");
        assertInvalid(post("/api/rules", rule("   ")), "名称");
        assertThat(get("/api/rules")).isEmpty();
    }

    @Test
    void otherInputIsValidated() {
        Map<String, Object> bad = rule("规则");
        bad.put("coef", -1);
        assertInvalid(post("/api/rules", bad), "系数");
        bad = rule("规则");
        bad.put("coef", 1.00001);
        assertInvalid(post("/api/rules", bad), "系数");
        bad = rule("规则");
        bad.put("kind", "OTHER");
        assertInvalid(post("/api/rules", bad), "属性");
        bad = rule("规则");
        bad.put("productId", 999999);
        assertInvalid(post("/api/rules", bad), "商品");
        bad = rule("规则");
        bad.put("specId", 999999);
        assertInvalid(post("/api/rules", bad), "规格");
        bad = rule("规则");
        bad.remove("tareBoxId");
        assertInvalid(post("/api/rules", bad), "包装箱");
        bad = rule("规则");
        bad.put("tareBoxId", 999999);
        assertInvalid(post("/api/rules", bad), "包装箱");
        bad = rule("规则");
        bad.put("lossJin", 0.125);
        assertInvalid(post("/api/rules", bad), "损耗");
        assertThat(get("/api/rules")).isEmpty();
    }

    @Test
    void createRulePacksSpecTareAndLoss() {
        // v7 WEB-05 验收：创建“粉蛋李四收购定价方案”；规则打包规格、皮重与每箱损耗
        Map<String, Object> body = rule("粉蛋李四收购定价方案");
        body.put("lossJin", -0.5);
        JsonNode r = created(body);
        assertThat(r.get("name").asText()).isEqualTo("粉蛋李四收购定价方案");
        assertThat(r.get("productName").asText()).isEqualTo("粉蛋");
        assertThat(r.get("specName").asText()).isEqualTo("360枚/箱");
        assertThat(r.get("stdNetJin").decimalValue()).isEqualByComparingTo("45");
        assertThat(r.get("tareBoxId").asLong()).isEqualTo(box2);
        assertThat(r.get("tareName").asText()).isEqualTo("2号包装箱");
        assertThat(r.get("tareJin").decimalValue()).isEqualByComparingTo("4");
        assertThat(r.get("lossJin").decimalValue()).isEqualByComparingTo("-0.5");
        assertThat(r.get("coef").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(r.get("enabled").asBoolean()).isTrue();
        assertThat(r.has("binds")).isFalse();

        // 损耗不传为 0
        assertThat(created(rule("粉蛋通用")).get("lossJin").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void duplicateNameConflictsAndPointsToTheOtherRule() {
        long first = created(rule("粉蛋李四收购定价方案")).get("id").asLong();
        var res = post("/api/rules", rule(" 粉蛋李四收购定价方案 "));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("code").asText()).isEqualTo("RULE_CONFLICT");
        assertThat(res.getBody().get("message").asText()).contains("粉蛋李四收购定价方案");
        assertThat(res.getBody().get("conflicts").get(0).get("ruleId").asLong()).isEqualTo(first);

        // 同样的商品规格可以有多条规则，只要名称不同；改名撞上别的规则也算冲突
        long second = created(rule("粉蛋李四优价方案")).get("id").asLong();
        assertThat(put("/api/rules/" + second, Map.of("name", "粉蛋李四收购定价方案")).getStatusCode().value()).isEqualTo(409);
        // 保存自己不算撞自己
        assertThat(put("/api/rules/" + first, Map.of("name", "粉蛋李四收购定价方案")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void patchOnlyChangesWhatIsSent_andScaleOperatorCanChangeCoefAndLoss() {
        long id = created(rule("粉蛋李四收购定价方案")).get("id").asLong();

        var res = call(HttpMethod.PUT, "/api/rules/" + id, Map.of("coef", 0.6, "lossJin", 1), scale());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("coef").decimalValue()).isEqualByComparingTo("0.6");
        assertThat(res.getBody().get("lossJin").decimalValue()).isEqualByComparingTo("1");
        assertThat(res.getBody().get("name").asText()).isEqualTo("粉蛋李四收购定价方案");
        assertThat(res.getBody().get("tareBoxId").asLong()).isEqualTo(box2);

        var moved = put("/api/rules/" + id, Map.of("specId", s240, "tareBoxId", box1, "lossJin", -1.5));
        assertThat(moved.getBody().get("specName").asText()).isEqualTo("240枚/箱");
        assertThat(moved.getBody().get("tareName").asText()).isEqualTo("1号包装箱");
        assertThat(moved.getBody().get("lossJin").decimalValue()).isEqualByComparingTo("-1.5");

        assertInvalid(put("/api/rules/" + id, Map.of("name", "  ")), "名称");
        assertInvalid(put("/api/rules/999999", Map.of("coef", 1)), "不存在");
        assertThat(get("/api/rules").get(0).get("coef").decimalValue()).isEqualByComparingTo("0.6");
    }

    @Test
    void operatorIsRecorded() {
        long id = created(rule("甲")).get("id").asLong();
        Long adminId = jdbc.queryForObject("SELECT id FROM account WHERE phone = '13700000001'", Long.class);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM pricing_rule WHERE id = ?", Long.class, id)).isEqualTo(adminId);
        call(HttpMethod.PUT, "/api/rules/" + id, Map.of("coef", 2), scale());
        Long scaleId = jdbc.queryForObject("SELECT id FROM account WHERE phone = '13700000002'", Long.class);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM pricing_rule WHERE id = ?", Long.class, id)).isEqualTo(scaleId);
    }

    // ---------------- 筛选 ----------------

    @Test
    void listFilters() {
        created(rule("粉蛋李四收购定价方案"));
        Map<String, Object> red240 = rule("红蛋王秀英小箱收购方案");
        red240.put("productId", red);
        red240.put("specId", s240);
        red240.put("tareBoxId", box1);
        created(red240);
        Map<String, Object> sell = rule("永丰市场粉蛋发货方案");
        sell.put("kind", "SELL");
        created(sell);
        Map<String, Object> off = rule("停用的方案");
        off.put("enabled", false);
        created(off);

        assertThat(get("/api/rules")).hasSize(4);
        assertThat(get("/api/rules?kind=SELL")).hasSize(1);
        assertThat(get("/api/rules?kind=BUY")).hasSize(3);
        assertThat(get("/api/rules?kind=BUY&enabled=true")).hasSize(2);
        assertThat(get("/api/rules?productId=" + red)).hasSize(1);
        assertThat(get("/api/rules?specId=" + s360)).hasSize(3);
        assertThat(get("/api/rules?kind=BUY&productId=" + pink + "&specId=" + s360)).hasSize(2);
        // 搜索：规则名、商品、规格、包装箱，多个词要同时命中
        assertThat(get("/api/rules?q=李四")).hasSize(1);
        assertThat(get("/api/rules?q=1号包装箱")).hasSize(1);
        assertThat(get("/api/rules?q=永丰")).hasSize(1);
        assertThat(get("/api/rules?q=红蛋 240")).hasSize(1);
        assertThat(get("/api/rules?q=没有这个")).isEmpty();
    }

    // ---------------- 谁能调 ----------------

    @Test
    void adminAndScaleCanCallButAnonymousCannot() {
        long id = created(rule("甲")).get("id").asLong();
        assertThat(call(HttpMethod.GET, "/api/rules", null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.PUT, "/api/rules/" + id, Map.of("coef", 3), null).getStatusCode().value()).isEqualTo(401);

        assertThat(call(HttpMethod.GET, "/api/rules?kind=BUY", null, scale()).getBody()).hasSize(1);
        // 绑农户的接口已经没有了
        assertThat(call(HttpMethod.GET, "/api/farmers/1/rules", null, scale()).getStatusCode().value()).isEqualTo(404);
    }
}

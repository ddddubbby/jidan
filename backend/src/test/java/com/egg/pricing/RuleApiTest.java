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
 * P1-5 验收：没填名称被拒；同一农户同一商品规格绑第二条返回 RULE_CONFLICT 并指出冲突规则；解绑后可保存。
 * 另外覆盖修改、筛选、按农户设置绑定，以及管理员与磅秤员都能调（开发规格 5.2：A+S）。
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

    private long pink, red, s360, s240, liSi, wang, yongfeng;

    @BeforeEach
    void fixtures() {
        for (String t : List.of("pricing_rule_bind", "pricing_rule", "vendor_phone", "vendor", "customer", "product", "spec")) {
            jdbc.update("DELETE FROM " + t);
        }
        pink = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0001', '粉蛋', '箱')");
        red = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0002', '红蛋', '箱')");
        s360 = insert("INSERT INTO spec (name, egg_count, std_net_kg, enabled) VALUES ('360枚/箱', 360, 45, 1)");
        s240 = insert("INSERT INTO spec (name, egg_count, std_net_kg, enabled) VALUES ('240枚/箱', 240, 30, 1)");
        liSi = insert("INSERT INTO vendor (hs_code, name) VALUES ('GYS0001', '李四')");
        wang = insert("INSERT INTO vendor (hs_code, name) VALUES ('GYS0002', '王秀英')");
        jdbc.update("INSERT INTO vendor_phone (vendor_id, phone, tail4) VALUES (?, '13603511234', '1234')", liSi);
        jdbc.update("INSERT INTO vendor_phone (vendor_id, phone, tail4) VALUES (?, '15803511234', '1234')", wang);
        yongfeng = insert("INSERT INTO customer (hs_code, name) VALUES ('KH0001', '郑州永丰农产品批发市场')");
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

    /** 默认是粉蛋、360枚/箱、系数 1 的收购规则。 */
    private Map<String, Object> rule(String name, Long... vendorIds) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("kind", "BUY");
        m.put("productId", pink);
        m.put("specId", s360);
        m.put("coef", 1);
        m.put("bindIds", List.of(vendorIds));
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
        assertInvalid(post("/api/rules", rule("规则", 999999L)), "农户");
        Map<String, Object> sell = rule("发货", liSi);
        sell.put("kind", "SELL");
        assertInvalid(post("/api/rules", sell), "客户");
        assertThat(get("/api/rules")).isEmpty();
    }

    @Test
    void createRuleAndBindFarmer() {
        // v7 WEB-05 验收：创建“粉蛋李四收购定价方案”并绑定农户李四
        JsonNode r = created(rule("粉蛋李四收购定价方案", liSi));
        assertThat(r.get("name").asText()).isEqualTo("粉蛋李四收购定价方案");
        assertThat(r.get("kind").asText()).isEqualTo("BUY");
        assertThat(r.get("productName").asText()).isEqualTo("粉蛋");
        assertThat(r.get("specName").asText()).isEqualTo("360枚/箱");
        assertThat(r.get("coef").decimalValue()).isEqualByComparingTo("1");
        assertThat(r.get("enabled").asBoolean()).isTrue();
        assertThat(r.get("bindCount").asInt()).isEqualTo(1);
        assertThat(r.get("binds").get(0).get("id").asLong()).isEqualTo(liSi);
        assertThat(r.get("binds").get(0).get("name").asText()).isEqualTo("李四");
        assertThat(r.get("binds").get(0).get("tail4").asText()).isEqualTo("1234");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pricing_rule_bind", Integer.class)).isEqualTo(1);
    }

    @Test
    void sellRuleBindsCustomerAndNeverConflicts() {
        Map<String, Object> sell = rule("永丰市场粉蛋发货方案", yongfeng);
        sell.put("kind", "SELL");
        JsonNode a = created(sell);
        assertThat(a.get("binds").get(0).get("name").asText()).isEqualTo("郑州永丰农产品批发市场");
        // 发货规则互不检查冲突；同一个客户在同商品规格上可以再绑一条
        sell.put("name", "永丰市场粉蛋发货方案 2");
        JsonNode b = created(sell);
        // 客户 id 与农户 id 可能相同（这里硬造一条同 id 的绑定），也不能把发货规则当成农户的规则
        jdbc.update("INSERT INTO pricing_rule_bind (rule_id, target_id) VALUES (?, ?)", b.get("id").asLong(), liSi);
        assertThat(get("/api/farmers/" + liSi + "/rules")).isEmpty();
    }

    // ---------------- 冲突 ----------------

    @Test
    void secondRuleForSameFarmerAndSpecConflictsUntilUnbound() {
        JsonNode first = created(rule("粉蛋李四收购定价方案", liSi));
        long firstId = first.get("id").asLong();

        var res = post("/api/rules", rule("粉蛋李四另一方案", liSi, wang));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("code").asText()).isEqualTo("RULE_CONFLICT");
        assertThat(res.getBody().get("message").asText()).contains("李四").contains("粉蛋李四收购定价方案");
        JsonNode c = res.getBody().get("conflicts");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).get("vendorId").asLong()).isEqualTo(liSi);
        assertThat(c.get(0).get("vendorName").asText()).isEqualTo("李四");
        assertThat(c.get(0).get("ruleId").asLong()).isEqualTo(firstId);
        assertThat(c.get(0).get("ruleName").asText()).isEqualTo("粉蛋李四收购定价方案");
        // 被拒绝的整条都不保存，连没冲突的王秀英也没绑上
        assertThat(get("/api/rules")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pricing_rule_bind", Integer.class)).isEqualTo(1);

        // 就地一键解绑：从冲突的那条规则上摘掉这个农户
        var unbound = put("/api/farmers/" + liSi + "/rule-binding",
                Map.of("productId", pink, "specId", s360));
        assertThat(unbound.getStatusCode().value()).isEqualTo(200);
        assertThat(unbound.getBody()).isEmpty();
        assertThat(get("/api/rules").get(0).get("bindCount").asInt()).isZero();

        JsonNode second = created(rule("粉蛋李四另一方案", liSi, wang));
        assertThat(second.get("bindCount").asInt()).isEqualTo(2);
    }

    @Test
    void conflictIsAlsoCheckedWhenBindingOnUpdate() {
        long a = created(rule("甲", liSi)).get("id").asLong();
        long b = created(rule("乙", wang)).get("id").asLong();

        var res = put("/api/rules/" + b, Map.of("bindIds", List.of(wang, liSi)));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("code").asText()).isEqualTo("RULE_CONFLICT");
        assertThat(res.getBody().get("conflicts").get(0).get("ruleId").asLong()).isEqualTo(a);

        // 从甲上解绑后可保存；也可以直接改甲的绑定列表来解开
        assertThat(put("/api/rules/" + a, Map.of("bindIds", List.of())).getStatusCode().value()).isEqualTo(200);
        var ok = put("/api/rules/" + b, Map.of("bindIds", List.of(wang, liSi)));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().get("bindCount").asInt()).isEqualTo(2);
    }

    @Test
    void sameFarmerOnDifferentProductOrSpecIsNotAConflict() {
        created(rule("粉蛋360", liSi));
        Map<String, Object> otherSpec = rule("粉蛋240", liSi);
        otherSpec.put("specId", s240);
        created(otherSpec);
        Map<String, Object> otherProduct = rule("红蛋360", liSi);
        otherProduct.put("productId", red);
        created(otherProduct);
        assertThat(get("/api/farmers/" + liSi + "/rules")).hasSize(3);
    }

    @Test
    void disabledRulesDoNotConflictUntilEnabled() {
        created(rule("甲", liSi));
        Map<String, Object> off = rule("乙（停用）", liSi);
        off.put("enabled", false);
        long b = created(off).get("id").asLong();

        var res = put("/api/rules/" + b, Map.of("enabled", true));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("code").asText()).isEqualTo("RULE_CONFLICT");
        // 停用的规则仍留在库里，没被误启用
        assertThat(jdbc.queryForObject("SELECT enabled FROM pricing_rule WHERE id = ?", Integer.class, b)).isZero();
    }

    @Test
    void changingSpecIntoAnOccupiedSpecConflicts() {
        created(rule("粉蛋360", liSi));
        Map<String, Object> m = rule("粉蛋240", liSi);
        m.put("specId", s240);
        long id = created(m).get("id").asLong();
        var res = put("/api/rules/" + id, Map.of("specId", s360));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("code").asText()).isEqualTo("RULE_CONFLICT");
    }

    // ---------------- 修改 ----------------

    @Test
    void patchOnlyChangesWhatIsSent_andScaleOperatorCanChangeCoef() {
        long id = created(rule("粉蛋李四收购定价方案", liSi)).get("id").asLong();

        var res = call(HttpMethod.PUT, "/api/rules/" + id, Map.of("coef", 1.2), scale());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("coef").decimalValue()).isEqualByComparingTo("1.2");
        assertThat(res.getBody().get("name").asText()).isEqualTo("粉蛋李四收购定价方案");
        assertThat(res.getBody().get("bindCount").asInt()).isEqualTo(1);

        assertInvalid(put("/api/rules/" + id, Map.of("name", "  ")), "名称");
        assertInvalid(put("/api/rules/999999", Map.of("coef", 1)), "不存在");
        assertThat(get("/api/rules").get(0).get("coef").decimalValue()).isEqualByComparingTo("1.2");
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

    @Test
    void changingKindClearsBindings() {
        long id = created(rule("甲", liSi)).get("id").asLong();
        var res = put("/api/rules/" + id, Map.of("kind", "SELL"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("kind").asText()).isEqualTo("SELL");
        assertThat(res.getBody().get("bindCount").asInt()).isZero();
        var bound = put("/api/rules/" + id, Map.of("bindIds", List.of(yongfeng)));
        assertThat(bound.getBody().get("binds").get(0).get("name").asText()).isEqualTo("郑州永丰农产品批发市场");
    }

    // ---------------- 筛选 ----------------

    @Test
    void listFilters() {
        created(rule("粉蛋李四收购定价方案", liSi));
        Map<String, Object> red240 = rule("红蛋王秀英小箱收购方案", wang);
        red240.put("productId", red);
        red240.put("specId", s240);
        created(red240);
        Map<String, Object> sell = rule("永丰市场粉蛋发货方案", yongfeng);
        sell.put("kind", "SELL");
        created(sell);

        assertThat(get("/api/rules")).hasSize(3);
        assertThat(get("/api/rules?kind=SELL")).hasSize(1);
        assertThat(get("/api/rules?kind=BUY")).hasSize(2);
        assertThat(get("/api/rules?productId=" + red)).hasSize(1);
        assertThat(get("/api/rules?specId=" + s360)).hasSize(2);
        assertThat(get("/api/rules?kind=BUY&productId=" + pink + "&specId=" + s360)).hasSize(1);
        // 搜索：规则名、农户名、客户名、农户尾号，多个词要同时命中
        assertThat(get("/api/rules?q=李四")).hasSize(1);
        assertThat(get("/api/rules?q=王秀英")).hasSize(1);
        assertThat(get("/api/rules?q=永丰")).hasSize(1);
        assertThat(get("/api/rules?q=1234")).hasSize(2);
        assertThat(get("/api/rules?q=1234 红蛋")).hasSize(1);
        assertThat(get("/api/rules?q=没有这个")).isEmpty();
    }

    // ---------------- 按农户设置绑定 ----------------

    @Test
    void setFarmerBindingSwitchesAndUnbinds() {
        long a = created(rule("甲", liSi)).get("id").asLong();
        long b = created(rule("乙")).get("id").asLong();
        assertThat(get("/api/farmers/" + liSi + "/rules")).hasSize(1);

        // 更换：设为乙，甲上不再有他
        var res = put("/api/farmers/" + liSi + "/rule-binding", Map.of("productId", pink, "specId", s360, "ruleId", b));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).hasSize(1);
        assertThat(res.getBody().get(0).get("id").asLong()).isEqualTo(b);
        JsonNode all = get("/api/rules");
        assertThat(all.get(0).get("id").asLong()).isEqualTo(a);
        assertThat(all.get(0).get("bindCount").asInt()).isZero();
        assertThat(all.get(1).get("bindCount").asInt()).isEqualTo(1);

        // 再设为乙是幂等的
        assertThat(put("/api/farmers/" + liSi + "/rule-binding", Map.of("productId", pink, "specId", s360, "ruleId", b))
                .getBody()).hasSize(1);

        // 解绑
        var unbound = put("/api/farmers/" + liSi + "/rule-binding", Map.of("productId", pink, "specId", s360));
        assertThat(unbound.getBody()).isEmpty();
        assertThat(get("/api/farmers/" + liSi + "/rules")).isEmpty();
    }

    @Test
    void setFarmerBindingOnlyTouchesThatProductAndSpec() {
        created(rule("粉蛋360", liSi));
        Map<String, Object> other = rule("粉蛋240", liSi);
        other.put("specId", s240);
        long keep = created(other).get("id").asLong();

        put("/api/farmers/" + liSi + "/rule-binding", Map.of("productId", pink, "specId", s360));
        JsonNode left = get("/api/farmers/" + liSi + "/rules");
        assertThat(left).hasSize(1);
        assertThat(left.get(0).get("id").asLong()).isEqualTo(keep);
    }

    @Test
    void setFarmerBindingRejectsWrongRule() {
        long buy = created(rule("甲")).get("id").asLong();
        Map<String, Object> sell = rule("发货");
        sell.put("kind", "SELL");
        long sellId = created(sell).get("id").asLong();
        Map<String, Object> off = rule("停用的");
        off.put("enabled", false);
        long offId = created(off).get("id").asLong();
        String url = "/api/farmers/" + liSi + "/rule-binding";

        assertInvalid(put(url, Map.of("productId", pink, "specId", s240, "ruleId", buy)), "不一致");
        assertInvalid(put(url, Map.of("productId", red, "specId", s360, "ruleId", buy)), "不一致");
        assertInvalid(put(url, Map.of("productId", pink, "specId", s360, "ruleId", sellId)), "发货规则");
        assertInvalid(put(url, Map.of("productId", pink, "specId", s360, "ruleId", offId)), "停用");
        assertInvalid(put(url, Map.of("productId", pink, "specId", s360, "ruleId", 999999)), "不存在");
        assertInvalid(put(url, Map.of("specId", s360)), "商品");
        assertInvalid(put("/api/farmers/999999/rule-binding", Map.of("productId", pink, "specId", s360)), "农户");
        assertInvalid(call(HttpMethod.GET, "/api/farmers/999999/rules", null, admin()), "农户");
        assertThat(get("/api/farmers/" + liSi + "/rules")).isEmpty();
    }

    // ---------------- 谁能调 ----------------

    @Test
    void adminAndScaleCanCallButAnonymousCannot() {
        long id = created(rule("甲", liSi)).get("id").asLong();
        assertThat(call(HttpMethod.GET, "/api/rules", null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.PUT, "/api/rules/" + id, Map.of("coef", 3), null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/farmers/" + liSi + "/rules", null, null).getStatusCode().value()).isEqualTo(401);

        assertThat(call(HttpMethod.GET, "/api/rules?kind=BUY", null, scale()).getBody()).hasSize(1);
        assertThat(call(HttpMethod.GET, "/api/farmers/" + liSi + "/rules", null, scale()).getBody()).hasSize(1);
        var unbound = call(HttpMethod.PUT, "/api/farmers/" + liSi + "/rule-binding",
                Map.of("productId", pink, "specId", s360), scale());
        assertThat(unbound.getStatusCode().value()).isEqualTo(200);
    }
}

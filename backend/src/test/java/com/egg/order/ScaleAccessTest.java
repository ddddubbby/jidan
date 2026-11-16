package com.egg.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * F1-1 验收：磅秤员 token 能调通 APP 用到的每一个接口，没有一个是 401 / 403。
 * 起因是 APP 曾调仅管理员的档案接口，磅秤员建不了单。接口表以 APP 的 {@code EggApi.kt} 为准：
 * 声明里多出一个路径而这里没有调用，测试就失败，所以 APP 以后再加接口必须同时在这里补一行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.admin.phone=13700000031", "egg.admin.password=boss-pass-3", "egg.admin.name=权限测试管理员"})
@Import(MySqlTestConfig.class)
class ScaleAccessTest {

    private static final Path EGG_API = Path.of("../android/app/src/main/java/com/jidan/receiving/data/EggApi.kt");

    /** 不走后端接口权限表的两条：登录本身是公开的；版本接口由部署用的 nginx 提供（deploy/nginx.conf），不在后端。 */
    private static final Set<String> NOT_BACKEND_GUARDED = Set.of("POST api/auth/login", "GET api/app/version");

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;

    private long pink, s360, liSi, warehouse, rule;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void fixtures() {
        for (String t : List.of("weigh_record", "purchase_order", "batch_seq", "pricing_rule_bind", "pricing_rule",
                "base_price", "vendor_phone", "vendor", "warehouse", "product", "spec", "tare_box", "`option`")) {
            jdbc.update("DELETE FROM " + t);
        }
        pink = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0001', '粉蛋', '箱')");
        s360 = insert("INSERT INTO spec (name, egg_count, std_net_kg, enabled) VALUES ('360枚/箱', 360, 45, 1)");
        liSi = insert("INSERT INTO vendor (hs_code, name) VALUES ('GYS0001', '李四')");
        jdbc.update("INSERT INTO vendor_phone (vendor_id, phone, tail4) VALUES (?, '13800001234', '1234')", liSi);
        warehouse = insert("INSERT INTO warehouse (hs_code, name) VALUES ('CK0001', '一号库')");
        jdbc.update("INSERT INTO tare_box (name, tare_kg, hotkey, enabled) VALUES ('2号包装箱', 2, 1, 1)");
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 180)", today, s360);
        rule = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, coef, enabled)"
                + " VALUES ('粉蛋收购定价方案', 'BUY', " + pink + ", " + s360 + ", 1, 1)");
    }

    private long insert(String sql) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }

    private String login(String phone, String password, String client) {
        return http.postForObject("/api/auth/login", Map.of("phone", phone, "password", password, "client", client),
                JsonNode.class).get("token").asText();
    }

    private ResponseEntity<JsonNode> call(HttpMethod m, String url, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange(url, m, new HttpEntity<>(body, h), JsonNode.class);
    }

    @Test
    void scaleOperatorCanCallEveryEndpointTheAppUses() throws IOException {
        String adminToken = login("13700000031", "boss-pass-3", "WEB");
        JsonNode created = call(HttpMethod.POST, "/api/accounts", Map.of("name", "权限测试磅秤员", "phone", "13700000032"),
                adminToken).getBody();
        String scale = login("13700000032", created.get("password").asText(), "APP");

        // 有了批次号才能提交；先建一箱可提交的单，之后 recent 才有人
        Map<String, Object> box = Map.of("seq", 1, "netKg", 46.0, "grossKg", 48.0, "tareKg", 2, "tareName", "2号包装箱",
                "groupNo", 1, "groupK", 1, "manual", false, "printed", true);

        // 键是 EggApi.kt 里的“方法 路径”，值是实际调用的结果
        Map<String, ResponseEntity<JsonNode>> got = new LinkedHashMap<>();
        got.put("GET api/app/bootstrap", call(HttpMethod.GET, "/api/app/bootstrap", null, scale));
        got.put("GET api/auth/me", call(HttpMethod.GET, "/api/auth/me", null, scale));
        got.put("GET api/farmers", call(HttpMethod.GET, "/api/farmers?tail=1234", null, scale));
        got.put("GET api/rules", call(HttpMethod.GET, "/api/rules?kind=BUY&productId=" + pink + "&specId=" + s360, null, scale));
        got.put("GET api/farmers/{id}/rules", call(HttpMethod.GET, "/api/farmers/" + liSi + "/rules", null, scale));
        got.put("PUT api/farmers/{id}/rule-binding", call(HttpMethod.PUT, "/api/farmers/" + liSi + "/rule-binding",
                Map.of("productId", pink, "specId", s360, "ruleId", rule), scale));
        got.put("PUT api/rules/{id}", call(HttpMethod.PUT, "/api/rules/" + rule, Map.of("coef", 1.1), scale));
        got.put("POST api/batches", call(HttpMethod.POST, "/api/batches", Map.of(), scale));
        String batchNo = got.get("POST api/batches").getBody().get("batchNo").asText();
        got.put("POST api/orders/quote", call(HttpMethod.POST, "/api/orders/quote",
                Map.of("vendorId", liSi, "productId", pink, "specId", s360, "boxNetKg", List.of(46.0, 45.0)), scale));
        got.put("POST api/orders", call(HttpMethod.POST, "/api/orders",
                Map.of("batchNo", batchNo, "vendorId", liSi, "productId", pink, "specId", s360,
                        "warehouseId", warehouse, "boxes", List.of(box)), scale));
        got.put("GET api/farmers/recent", call(HttpMethod.GET, "/api/farmers/recent", null, scale));

        got.forEach((api, res) -> assertThat(res.getStatusCode().value())
                .as(api + " 磅秤员应能调用，实际：" + res.getBody()).isBetween(200, 299));

        // bootstrap 要把建单与称重用的东西一次给齐：商品、规格（含标准净含量与当日基准价）、仓库、皮重热键
        JsonNode b = got.get("GET api/app/bootstrap").getBody();
        assertThat(b.get("products")).hasSize(1);
        assertThat(b.get("warehouses")).hasSize(1);
        assertThat(b.get("tares")).hasSize(1);
        assertThat(b.get("specs").get(0).get("stdNetKg").decimalValue()).isEqualByComparingTo("45");
        assertThat(b.get("specs").get(0).get("basePrice").decimalValue()).isEqualByComparingTo("180");
        assertThat(got.get("GET api/farmers/recent").getBody()).hasSize(1);

        // 守卫：EggApi.kt 声明的每个后端接口都在上面调过
        Matcher m = Pattern.compile("@(GET|POST|PUT|DELETE|PATCH)\\(\"([^\"]+)\"\\)").matcher(Files.readString(EGG_API));
        Set<String> declared = new TreeSet<>();
        while (m.find()) {
            declared.add(m.group(1) + " " + m.group(2));
        }
        declared.removeAll(NOT_BACKEND_GUARDED);
        assertThat(declared).as("EggApi.kt 里声明的接口").isNotEmpty();
        assertThat(got.keySet()).as("EggApi.kt 声明了、但这里没调用的接口").containsAll(declared);
    }
}

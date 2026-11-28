package com.egg.chanjet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.egg.MySqlTestConfig;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * P8-4 验收：CHANJET_MODE=real 且一项密钥都不填，服务正常启动；档案同步与审核回传都失败，并显示清楚的原因。
 * 全程没有向任何外部地址发请求（缺密钥在发请求前就失败）。真实账套未经实物验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.chanjet.mode=real", "egg.chanjet.app-key=", "egg.chanjet.app-secret=",
                "egg.chanjet.book-id=", "egg.chanjet.open-token=", "egg.chanjet.token-mode=static", "egg.admin.phone=13700000081",
                "egg.admin.password=boss-pass-8", "egg.admin.name=真实模式测试管理员"})
@Import(MySqlTestConfig.class)
class RealModeStartupTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ChanjetClient chanjet;

    private ResponseEntity<JsonNode> call(HttpMethod m, String url, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return http.exchange(url, m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private String token(String phone, String password, String client) {
        return http.postForObject("/api/auth/login", Map.of("phone", phone, "password", password, "client", client),
                JsonNode.class).get("token").asText();
    }

    private long insert(String sql) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }

    @Test
    void startsInRealModeAndSyncAndPushFailWithClearReasons() {
        assertThat(chanjet).isInstanceOf(RealChanjetClient.class);
        String admin = token("13700000081", "boss-pass-8", "WEB");

        // 管理页的“当前模式”
        JsonNode meta = call(HttpMethod.GET, "/api/meta", null, admin).getBody();
        assertThat(meta.get("chanjetMode").asText()).isEqualTo("real");
        assertThat(meta.get("bookName").asText()).isEqualTo("未配置账套");

        // 同步：400，原因里写明缺哪几项，且保留了上次的数据
        ResponseEntity<JsonNode> sync = call(HttpMethod.POST, "/api/archive/sync", null, admin);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String msg = sync.getBody().get("message").asText();
        assertThat(msg).startsWith("同步失败，保留了上次的数据：").contains("CHANJET_APP_KEY", "CHANJET_APP_SECRET",
                "CHANJET_BOOK_ID", "CHANJET_OPEN_TOKEN");

        // 审核：审核本身成功，回传失败，单据留在 APPROVED，push_error 写明原因
        for (String t : List.of("weigh_record", "purchase_order", "batch_seq", "pricing_rule_bind", "pricing_rule",
                "base_price", "vendor_phone", "vendor", "warehouse", "product", "spec", "tare_box", "`option`")) {
            jdbc.update("DELETE FROM " + t);
        }
        long product = insert("INSERT INTO product (hs_code, name, unit_name) VALUES ('SP0001', '粉蛋', '箱')");
        long spec = insert("INSERT INTO spec (name, egg_count, std_net_kg, enabled) VALUES ('360枚/箱', 360, 45, 1)");
        long vendor = insert("INSERT INTO vendor (hs_code, name) VALUES ('GYS0001', '李四')");
        long warehouse = insert("INSERT INTO warehouse (hs_code, name) VALUES ('CK0001', '一号库')");
        jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) VALUES (?, ?, 180)", java.time.LocalDate.now(), spec);
        long rule = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, coef, enabled)"
                + " VALUES ('粉蛋李四收购定价方案', 'BUY', " + product + ", " + spec + ", 1, 1)");
        jdbc.update("INSERT INTO pricing_rule_bind (rule_id, target_id) VALUES (?, ?)", rule, vendor);

        String batchNo = call(HttpMethod.POST, "/api/batches", null, admin).getBody().get("batchNo").asText();
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> b = new HashMap<>();
            b.put("seq", i);
            b.put("netKg", 46.0);
            b.put("grossKg", 48.0);
            b.put("tareKg", 2);
            b.put("tareName", "2号包装箱");
            b.put("groupNo", i);
            b.put("groupK", 1);
            b.put("manual", false);
            b.put("printed", true);
            boxes.add(b);
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/api/orders", Map.of("batchNo", batchNo, "vendorId", vendor,
                "productId", product, "specId", spec, "warehouseId", warehouse, "boxes", boxes), admin);
        assertThat(created.getStatusCode().is2xxSuccessful()).as(String.valueOf(created.getBody())).isTrue();
        long id = created.getBody().get("id").asLong();

        JsonNode approved = call(HttpMethod.POST, "/api/orders/" + id + "/approve", null, admin).getBody();
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("pushError").asText()).contains("CHANJET_OPEN_TOKEN").contains("没有向好生意发出任何请求");
        assertThat(approved.get("hsId").isNull()).isTrue();

        // 再点回传，同样说清楚原因，状态不变
        JsonNode again = call(HttpMethod.POST, "/api/orders/" + id + "/push", null, admin).getBody();
        assertThat(again.get("status").asText()).isEqualTo("APPROVED");
        assertThat(again.get("pushError").asText()).contains("CHANJET_APP_KEY");
    }
}

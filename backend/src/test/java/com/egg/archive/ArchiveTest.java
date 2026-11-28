package com.egg.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.egg.MySqlTestConfig;
import com.egg.chanjet.ChanjetClient;
import com.egg.chanjet.ChanjetException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * P1-4 验收：同步后能检索到“李四”；尾号 1234 返回李四与王秀英；再同步一次不产生重复。
 * 另外核对：同步失败保留旧数据、好生意里删掉的档案在本系统保留、尾号与最近农户的权限与排序。
 * 好生意用的是 FakeChanjetClient，未经实物验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.admin.phone=13700000001", "egg.admin.password=boss-pass-1", "egg.admin.name=测试管理员"})
@Import(MySqlTestConfig.class)
class ArchiveTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    ChanjetClient chanjet;

    private static String adminToken;
    private static String scaleToken;
    private static long scaleId;

    @BeforeEach
    void setUp() {
        if (adminToken == null) {
            adminToken = login("13700000001", "boss-pass-1", "WEB");
            JsonNode created = call(adminToken, HttpMethod.POST, "/api/accounts", Map.of("name", "测试磅秤员", "phone", "13900009999")).getBody();
            scaleId = created.get("account").get("id").asLong();
            scaleToken = login("13900009999", created.get("password").asText(), "APP");
        }
        for (String t : List.of("purchase_order", "vendor_phone", "vendor", "product", "customer", "warehouse")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    private String login(String phone, String password, String client) {
        return http.postForObject("/api/auth/login", Map.of("phone", phone, "password", password, "client", client),
                JsonNode.class).get("token").asText();
    }

    private ResponseEntity<JsonNode> call(String token, HttpMethod method, String url, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange(url, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    private JsonNode get(String token, String url) {
        ResponseEntity<JsonNode> res = call(token, HttpMethod.GET, url, null);
        assertThat(res.getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private void sync() {
        assertThat(call(adminToken, HttpMethod.POST, "/api/archive/sync", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    @Test
    void 同步前没有档案_同步后能检索到李四() {
        assertThat(get(adminToken, "/api/archive/vendors?q=李四").get("total").asInt()).isZero();
        assertThat(get(adminToken, "/api/archive/status").get("lastSyncedAt").isNull()).isTrue();

        JsonNode status = call(adminToken, HttpMethod.POST, "/api/archive/sync", null).getBody();
        assertThat(status.get("vendors").asInt()).isEqualTo(4);
        assertThat(status.get("products").asInt()).isEqualTo(2);
        assertThat(status.get("customers").asInt()).isEqualTo(2);
        assertThat(status.get("warehouses").asInt()).isEqualTo(2);
        assertThat(status.get("lastSyncedAt").isNull()).isFalse();

        JsonNode found = get(adminToken, "/api/archive/vendors?q=李四");
        assertThat(found.get("total").asInt()).isEqualTo(2);
        assertThat(texts(found.get("items"), "hsCode")).containsExactly("GYS0001", "GYS0004");
        // 按号码、编码也能搜到；其余三类同样可检索
        assertThat(texts(get(adminToken, "/api/archive/vendors?q=13935107788").get("items"), "name")).containsExactly("王秀英");
        assertThat(texts(get(adminToken, "/api/archive/vendors?q=GYS0003").get("items"), "name")).containsExactly("张三");
        assertThat(texts(get(adminToken, "/api/archive/products?q=粉").get("items"), "name")).containsExactly("粉蛋");
        assertThat(texts(get(adminToken, "/api/archive/customers?q=新发地").get("items"), "hsCode")).containsExactly("KH0002");
        assertThat(texts(get(adminToken, "/api/archive/warehouses").get("items"), "name")).containsExactly("1号仓库", "2号仓库");
        assertThat(get(adminToken, "/api/archive/products").get("items").get(0).get("unitName").asText()).isEqualTo("箱");
    }

    @Test
    void 分页() {
        sync();
        JsonNode p2 = get(adminToken, "/api/archive/vendors?page=2&pageSize=3");
        assertThat(p2.get("total").asInt()).isEqualTo(4);
        assertThat(p2.get("page").asInt()).isEqualTo(2);
        assertThat(texts(p2.get("items"), "hsCode")).containsExactly("GYS0004");
    }

    @Test
    void 不认识的档案类型被拒() {
        ResponseEntity<JsonNode> res = call(adminToken, HttpMethod.GET, "/api/archive/orders", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID");
    }

    @Test
    void 再同步一次不产生重复() {
        sync();
        sync();
        for (String t : List.of("product", "vendor", "customer", "warehouse")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class)).as(t).isEqualTo(
                    t.equals("vendor") ? 4 : 2);
        }
        // 王秀英两个号码、其余各一个，共 5 条，没有翻倍
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vendor_phone", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vendor_phone WHERE tail4 = '1234'", Integer.class)).isEqualTo(2);
    }

    @Test
    void 同步按编码覆盖更新_好生意里没有的档案与号码处理() {
        sync();
        jdbc.update("UPDATE vendor SET name = '改错了' WHERE hs_code = 'GYS0001'");
        jdbc.update("INSERT INTO vendor_phone (vendor_id, phone, tail4) SELECT id, '19999990000', '0000' FROM vendor WHERE hs_code = 'GYS0001'");
        jdbc.update("INSERT INTO vendor (hs_code, name) VALUES ('GYS-OLD', '好生意里已删除的农户')");
        sync();
        assertThat(jdbc.queryForObject("SELECT name FROM vendor WHERE hs_code = 'GYS0001'", String.class)).isEqualTo("李四");
        // 号码以好生意为准，多出来的被清掉
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vendor_phone WHERE tail4 = '0000'", Integer.class)).isZero();
        // 只增改不删：历史单据可能还引用着它
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vendor WHERE hs_code = 'GYS-OLD'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 某一类同步失败时其他类照常写入_失败的那类保留旧数据并写明是哪一类() {
        sync();
        jdbc.update("UPDATE product SET name = '旧数据' WHERE hs_code = 'SP0001'");
        jdbc.update("UPDATE vendor SET name = '旧农户' WHERE hs_code = 'GYS0001'");
        doThrow(new ChanjetException("好生意返回：appSecret 校验失败（openapi.e1001）")).when(chanjet).listVendors();

        ResponseEntity<JsonNode> res = call(adminToken, HttpMethod.POST, "/api/archive/sync", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID");
        assertThat(res.getBody().get("message").asText()).contains("商品、客户、仓库已更新")
                .contains("农户档案没有同步").contains("appSecret 校验失败").contains("保留了上次的数据");
        // 商品照常写入；农户保持上次的数据
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE hs_code = 'SP0001'", String.class)).isNotEqualTo("旧数据");
        assertThat(jdbc.queryForObject("SELECT name FROM vendor WHERE hs_code = 'GYS0001'", String.class)).isEqualTo("旧农户");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vendor", Integer.class)).isEqualTo(4);
    }

    @Test
    void 四类都失败且原因相同时只说一遍并保留旧数据() {
        sync();
        jdbc.update("UPDATE product SET name = '旧数据' WHERE hs_code = 'SP0001'");
        ChanjetException e = new ChanjetException("好生意返回：appSecret 校验失败（openapi.e1001）");
        doThrow(e).when(chanjet).listProducts();
        doThrow(e).when(chanjet).listVendors();
        doThrow(e).when(chanjet).listCustomers();
        doThrow(e).when(chanjet).listWarehouses();

        ResponseEntity<JsonNode> res = call(adminToken, HttpMethod.POST, "/api/archive/sync", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("message").asText()).isEqualTo("同步失败，保留了上次的数据：好生意返回：appSecret 校验失败（openapi.e1001）");
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE hs_code = 'SP0001'", String.class)).isEqualTo("旧数据");
    }

    @Test
    void 尾号1234返回李四与王秀英_带匹配到的号码() {
        sync();
        JsonNode r = get(scaleToken, "/api/farmers?tail=1234");
        assertThat(texts(r, "name")).containsExactly("李四", "王秀英");
        assertThat(r.get(0).get("matchedPhones").get(0).asText()).isEqualTo("13603511234");
        // 王秀英有两个号码，只有后四位是 1234 的那个算匹配，另一个仍在 phones 里
        assertThat(r.get(1).get("matchedPhones")).hasSize(1);
        assertThat(r.get(1).get("matchedPhones").get(0).asText()).isEqualTo("15803511234");
        assertThat(r.get(1).get("phones")).hasSize(2);
    }

    @Test
    void 同名农户靠号码区分_无匹配返回空列表() {
        sync();
        JsonNode r = get(scaleToken, "/api/farmers?tail=5678");
        assertThat(texts(r, "name")).containsExactly("张三", "李四");
        assertThat(r.get(1).get("hsCode").asText()).isEqualTo("GYS0004");
        assertThat(get(scaleToken, "/api/farmers?tail=0000")).isEmpty();
    }

    @Test
    void 按名称找农户_汉字_编码_拼音首字母_没号码的也能找到() {
        sync();
        jdbc.update("INSERT INTO vendor (hs_code, name) VALUES ('GYS001', '湖南湘食双茂食品有限公司')");
        assertThat(texts(get(scaleToken, "/api/farmers?q=李"), "name")).containsExactly("李四", "李四");
        assertThat(texts(get(scaleToken, "/api/farmers?q=湘食"), "name")).containsExactly("湖南湘食双茂食品有限公司");
        assertThat(texts(get(scaleToken, "/api/farmers?q=GYS001"), "hsCode")).containsExactly("GYS001");
        // 拼音首字母：任意位置包含都算，不分大小写
        assertThat(texts(get(scaleToken, "/api/farmers?q=wxy"), "name")).containsExactly("王秀英");
        assertThat(texts(get(scaleToken, "/api/farmers?q=XY"), "name")).contains("王秀英");
        assertThat(texts(get(scaleToken, "/api/farmers?q=hnxs"), "name")).containsExactly("湖南湘食双茂食品有限公司");
        // 号码也能命中（与 Web 档案页同一套条件）；结果带全部号码，matchedPhones 为空
        JsonNode r = get(scaleToken, "/api/farmers?q=王秀英");
        assertThat(r.get(0).get("phones")).hasSize(2);
        assertThat(r.get(0).get("matchedPhones")).isEmpty();
        assertThat(get(scaleToken, "/api/farmers?q=没有这个人")).isEmpty();
        assertThat(get(scaleToken, "/api/farmers?q= ")).isEmpty();
    }

    @Test
    void 尾号不是四位数字被拒() {
        for (String url : List.of("/api/farmers?tail=12", "/api/farmers?tail=abcd", "/api/farmers")) {
            ResponseEntity<JsonNode> res = call(scaleToken, HttpMethod.GET, url, null);
            assertThat(res.getStatusCode()).as(url).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID");
        }
    }

    @Test
    void 最近农户_只算自己收过的_作废不算_最近的在前_最多五位() {
        sync();
        List<Long> ids = jdbc.queryForList("SELECT id FROM vendor ORDER BY hs_code", Long.class);
        for (int i = 0; i < 6; i++) {
            jdbc.update("INSERT INTO vendor (hs_code, name) VALUES (?, ?)", "GYS-X" + i, "农户" + i);
        }
        List<Long> extra = jdbc.queryForList("SELECT id FROM vendor WHERE hs_code LIKE 'GYS-X%' ORDER BY id", Long.class);
        // 依次收：张三、李四(GYS0001)、再收一次张三、农户0..5；另有别人收的和已作废的
        order(ids.get(2), scaleId, "PENDING");
        order(ids.get(0), scaleId, "APPROVED");
        order(ids.get(2), scaleId, "PUSHED");
        order(ids.get(1), scaleId + 1000, "PENDING");
        for (Long id : extra) {
            order(id, scaleId, "PENDING");
        }
        order(ids.get(3), scaleId, "VOID");

        JsonNode r = get(scaleToken, "/api/farmers/recent");
        assertThat(texts(r, "name")).containsExactly("农户5", "农户4", "农户3", "农户2", "农户1");
        assertThat(r.get(0).get("matchedPhones")).isEmpty();

        jdbc.update("DELETE FROM purchase_order WHERE vendor_id IN (SELECT id FROM vendor WHERE hs_code LIKE 'GYS-X%')");
        assertThat(texts(get(scaleToken, "/api/farmers/recent"), "name")).containsExactly("张三", "李四");
    }

    private int seq;

    private void order(long vendorId, long operatorId, String status) {
        seq++;
        jdbc.update("INSERT INTO purchase_order (code, batch_no, biz_date, vendor_id, warehouse_id, product_id, spec_id, source,"
                + " operator_id, status) VALUES (?, ?, CURRENT_DATE, ?, 1, 1, 1, 'APP', ?, ?)",
                "CGRK" + seq, "SG" + seq, vendorId, operatorId, status);
    }

    @Test
    void 权限_磅秤员能查农户不能同步_管理员不能查最近农户() {
        assertThat(call(scaleToken, HttpMethod.POST, "/api/archive/sync", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(scaleToken, HttpMethod.GET, "/api/archive/vendors", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(adminToken, HttpMethod.GET, "/api/farmers?tail=1234", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(adminToken, HttpMethod.GET, "/api/farmers/recent", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/api/farmers?tail=1234", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meta返回好生意模式与账套名() {
        JsonNode meta = get(adminToken, "/api/meta");
        assertThat(meta.get("chanjetMode").asText()).isEqualTo("fake");
        assertThat(meta.get("bookName").asText()).isEqualTo("鑫源蛋品 2026 账套");
    }
}

package com.egg.chanjet;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * P8-5 验收：真实模式、Token 安全级别“高”（填了消息加解密密钥与软证书）时，消息接收地址不用登录就能收推送、
 * appTicket 落进 chanjet_auth；状态接口只给管理员。推送报文按 {@link TicketProtocol} 的推测构造，未经实物验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"egg.chanjet.mode=real", "egg.chanjet.app-key=key-abc", "egg.chanjet.app-secret=s",
                "egg.chanjet.book-id=B", "egg.chanjet.certificate=CERT-1", "egg.chanjet.encrypt-key=" + TicketModeTest.KEY,
                "egg.chanjet.base-url=http://127.0.0.1:9", "egg.admin.phone=13700000082",
                "egg.admin.password=boss-pass-8", "egg.admin.name=高级别测试管理员"})
@Import(MySqlTestConfig.class)
class TicketEndpointTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    AuthStore store;

    private ResponseEntity<JsonNode> call(HttpMethod m, String url, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return http.exchange(url, m, new HttpEntity<>(body, h), JsonNode.class);
    }

    @Test
    void pushIsPublicAndLandsInTheTableStatusIsAdminOnly() throws Exception {
        String cipher = TicketProtocol.encrypt("{\"appKey\":\"key-abc\",\"msgType\":\"APP_TICKET\","
                + "\"bizContent\":{\"appTicket\":\"TK-it\"}}", TicketModeTest.KEY);

        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/chanjet/ticket", Map.of("encryptMsg", cipher), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("result").asText()).isEqualTo("success");
        assertThat(store).isInstanceOf(JdbcAuthStore.class);
        assertThat(store.load("key-abc").appTicket()).isEqualTo("TK-it");

        ResponseEntity<JsonNode> bad = call(HttpMethod.POST, "/api/chanjet/ticket", Map.of("encryptMsg", "AAAA"), null);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(call(HttpMethod.GET, "/api/chanjet/status", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String admin = http.postForObject("/api/auth/login",
                Map.of("phone", "13700000082", "password", "boss-pass-8", "client", "WEB"), JsonNode.class)
                .get("token").asText();
        JsonNode s = call(HttpMethod.GET, "/api/chanjet/status", null, admin).getBody();
        assertThat(s.get("tokenMode").asText()).isEqualTo("ticket");
        assertThat(s.get("ticketFresh").asBoolean()).isTrue();
        assertThat(s.get("ready").asBoolean()).isTrue();
    }

    @Test
    void checkFileInStaticIsServedWithoutLogin() {
        // 开放平台的校验文件放在 resources/static/，后端直接提供，不用登录
        ResponseEntity<String> r = http.getForEntity("/CHANJET_CHECK_test.txt", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo("egg-check-test");
    }
}

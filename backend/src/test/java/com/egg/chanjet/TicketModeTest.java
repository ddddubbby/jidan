package com.egg.chanjet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.egg.chanjet.RealChanjetClient.TokenMode;
import com.egg.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Token 安全级别“高”：CHANJET_TOKEN_MODE=ticket 与开放平台消息接收地址（P8-5）。
 * 假的好生意服务器照“自建应用获取token”文档的响应示例写；推送报文、AES 写法、refresh 接口按 {@link TicketProtocol}
 * 里的推测写，密钥是测试里随手编的。全部只在模拟环境里验证，未经实物验证。
 */
class TicketModeTest {

    record Seen(String method, String path, String query, String body) {
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-27T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 测试用的 16 字节密钥，不是任何真实应用的。 */
    static final String KEY = "test-key-16bytes";
    static final String APP = "key-abc";

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile Function<Seen, Object[]> handler = s -> new Object[] { 200, "{}" };
    private final ObjectMapper json = new ObjectMapper();
    private final TestClock clock = new TestClock();
    private final AuthStore store = AuthStore.memory();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen s = new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), body);
            seen.add(s);
            Object[] r = handler.apply(s);
            byte[] bytes = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders((Integer) r[0], bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RealChanjetClient client(TokenMode mode, String encryptKey) {
        return new RealChanjetClient(new RealChanjetClient.Settings("http://127.0.0.1:" + server.getAddress().getPort(),
                APP, "secret-xyz-123", "BOOK1", "", "CERT-0123456789", encryptKey, mode, Duration.ofSeconds(5)), json,
                store, clock);
    }

    private RealChanjetClient ticket() {
        return client(TokenMode.AUTO, KEY);
    }

    private TicketController controller(ChanjetClient c) {
        return new TicketController(c, store, json, APP, KEY, clock);
    }

    private List<Seen> to(String part) {
        return seen.stream().filter(s -> s.path().contains(part)).toList();
    }

    private JsonNode body(Seen s) {
        try {
            return json.readTree(s.body());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String tokenReply(String token, long expiresIn, String refresh, long refreshExpiresIn) {
        return "{\"result\":true,\"value\":{\"expiresIn\":" + expiresIn + ",\"accessToken\":\"" + token + "\","
                + "\"refreshToken\":\"" + refresh + "\",\"refreshExpiresIn\":" + refreshExpiresIn + "}}";
    }

    private static final String ADD_OK = "{\"success\":true,\"code\":\"openapi.e0000\",\"data\":{\"id\":9001}}";

    /** 模拟开放平台推一条消息。 */
    private String push(TicketController c, String plain, String key) throws Exception {
        return c.receiveTicket(json.writeValueAsString(java.util.Map.of("encryptMsg", TicketProtocol.encrypt(plain, key))));
    }

    private static String ticketMsg(String app, String ticket) {
        return "{\"id\":\"m1\",\"appKey\":\"" + app + "\",\"msgType\":\"APP_TICKET\",\"time\":\"1764230400000\","
                + "\"bizContent\":{\"appTicket\":\"" + ticket + "\"}}";
    }

    // ---------------------------------------------------------------- 消息接收地址

    @Test
    void appTicketPushIsDecryptedAndStored() throws Exception {
        String reply = push(controller(ticket()), ticketMsg(APP, "TK-1"), KEY);

        assertThat(json.readTree(reply).get("result").asText()).isEqualTo("success");
        assertThat(store.load(APP).appTicket()).isEqualTo("TK-1");
        assertThat(store.load(APP).ticketAt()).isEqualTo(clock.now);
    }

    @Test
    void newerTicketOverwritesTheOldOne() throws Exception {
        TicketController c = controller(ticket());
        push(c, ticketMsg(APP, "TK-1"), KEY);
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        push(c, ticketMsg(APP, "TK-2"), KEY);

        assertThat(store.load(APP).appTicket()).isEqualTo("TK-2");
        assertThat(store.load(APP).ticketAt()).isEqualTo(clock.now);
    }

    @Test
    void connectivityTestAndOtherAppsAreAcknowledgedButNotStored() throws Exception {
        TicketController c = controller(ticket());

        assertThat(push(c, "{\"appKey\":\"" + APP + "\",\"msgType\":\"APP_TEST\",\"bizContent\":{}}", KEY))
                .contains("success");
        assertThat(push(c, ticketMsg("other-app", "TK-x"), KEY)).contains("success");
        assertThat(store.load(APP).appTicket()).isNull();
        assertThat(store.load("other-app").appTicket()).isNull();
    }

    @Test
    void wrongKeyOrGarbageIs400() {
        TicketController c = controller(ticket());

        assertThatThrownBy(() -> push(c, ticketMsg(APP, "TK-1"), "another-key-16by")).isInstanceOf(ApiException.class)
                .hasMessageContaining("CHANJET_ENCRYPT_KEY");
        assertThatThrownBy(() -> c.receiveTicket("{\"encryptMsg\":\"not base64 !!\"}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> c.receiveTicket("{}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> c.receiveTicket("{\"encryptMsg\":\"\"}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> c.receiveTicket(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new TicketController(ticket(), store, json, APP, "", clock).receiveTicket("{}"))
                .isInstanceOf(ApiException.class).hasMessageContaining("没有配置 CHANJET_ENCRYPT_KEY");
        assertThat(store.load(APP).appTicket()).isNull();
    }

    // ---------------------------------------------------------------- 取法

    @Test
    void autoPicksTicketWhenEncryptKeyAndCertificateAreBothSet() {
        assertThat(client(TokenMode.AUTO, KEY).mode()).isEqualTo(TokenMode.TICKET);
        assertThat(client(TokenMode.AUTO, "").mode()).isEqualTo(TokenMode.CERTIFICATE);
        assertThat(client(TokenMode.TICKET, "").mode()).isEqualTo(TokenMode.TICKET);
        assertThat(TokenMode.parse(" Ticket ")).isEqualTo(TokenMode.TICKET);
    }

    @Test
    void ticketModeNamesMissingEncryptKeyOrCertificate() {
        RealChanjetClient noKey = client(TokenMode.TICKET, "");
        assertThatThrownBy(noKey::listProducts).hasMessageContaining("缺少 CHANJET_ENCRYPT_KEY")
                .hasMessageNotContaining("CHANJET_CERTIFICATE");

        RealChanjetClient noCert = new RealChanjetClient(new RealChanjetClient.Settings("http://127.0.0.1:1", APP, "s",
                "B", "", "", KEY, TokenMode.AUTO, Duration.ofSeconds(1)), json, store, clock);
        assertThatThrownBy(noCert::listProducts).hasMessageContaining("缺少 CHANJET_CERTIFICATE");
        assertThat(seen).isEmpty();
    }

    // ---------------------------------------------------------------- 换票

    @Test
    void withoutTicketNothingIsSent() {
        assertThatThrownBy(() -> ticket().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("还没有收到开放平台推送的 appTicket").hasMessageContaining("/api/chanjet/ticket");
        assertThat(seen).isEmpty();
    }

    @Test
    void expiredTicketIsNotSent() {
        store.saveTicket(APP, "TK-old", clock.now);
        clock.now = clock.now.plus(Duration.ofMinutes(31));

        assertThatThrownBy(() -> ticket().listWarehouses()).hasMessageContaining("31 分钟前").hasMessageContaining("过期");
        assertThat(seen).isEmpty();
    }

    @Test
    void ticketAndCertificateAreSentTogetherAndTheTokenIsStored() {
        handler = s -> s.path().endsWith("/generateToken") ? new Object[] { 200, tokenReply("AT-1", 518400, "RT-1", 2512799) }
                : new Object[] { 200, ADD_OK };
        store.saveTicket(APP, "TK-1", clock.now);
        RealChanjetClient c = ticket();

        assertThat(c.addPurchaseStockIn("{}")).isEqualTo("9001");
        c.addPurchaseStockIn("{}");

        List<Seen> tokenCalls = to("/generateToken");
        assertThat(tokenCalls).hasSize(1);
        assertThat(tokenCalls.get(0).path()).isEqualTo("/v1/common/auth/selfBuiltApp/generateToken");
        assertThat(body(tokenCalls.get(0)).get("appTicket").asText()).isEqualTo("TK-1");
        assertThat(body(tokenCalls.get(0)).get("certificate").asText()).isEqualTo("CERT-0123456789");
        AuthStore.State s = store.load(APP);
        assertThat(s.accessToken()).isEqualTo("AT-1");
        assertThat(s.refreshToken()).isEqualTo("RT-1");
        assertThat(s.accessUntil()).isEqualTo(clock.now.plus(Duration.ofSeconds(518400)).minus(Duration.ofHours(1)));
    }

    @Test
    void storedTokenSurvivesRestartWithoutTicket() {
        handler = s -> s.path().endsWith("/generateToken") ? new Object[] { 200, tokenReply("AT-1", 518400, "RT-1", 2512799) }
                : new Object[] { 200, ADD_OK };
        store.saveTicket(APP, "TK-1", clock.now);
        ticket().addPurchaseStockIn("{}");
        store.clearTicket(APP);
        clock.now = clock.now.plus(Duration.ofDays(2));

        ticket().addPurchaseStockIn("{}"); // 新实例 = 重启

        assertThat(to("/generateToken")).hasSize(1);
        assertThat(to("/purchasestockin/")).hasSize(2);
    }

    @Test
    void expiredAccessIsRefreshedWithoutTicket() {
        AtomicInteger n = new AtomicInteger();
        handler = s -> {
            if (s.path().endsWith("/generateToken")) {
                return new Object[] { 200, tokenReply("AT-1", 518400, "RT-1", 2512799) };
            }
            if (s.path().equals(TicketProtocol.PATH_REFRESH)) {
                return new Object[] { 200, tokenReply("AT-R" + n.incrementAndGet(), 518400, "RT-2", 2512799) };
            }
            return new Object[] { 200, ADD_OK };
        };
        store.saveTicket(APP, "TK-1", clock.now);
        ticket().addPurchaseStockIn("{}");
        store.clearTicket(APP);
        clock.now = clock.now.plus(Duration.ofDays(6));

        ticket().addPurchaseStockIn("{}");

        List<Seen> refresh = to(TicketProtocol.PATH_REFRESH);
        assertThat(refresh).hasSize(1);
        assertThat(refresh.get(0).method()).isEqualTo("GET");
        assertThat(refresh.get(0).query()).isEqualTo("grantType=refresh_token&refreshToken=RT-1");
        assertThat(store.load(APP).accessToken()).isEqualTo("AT-R1");
        assertThat(store.load(APP).refreshToken()).isEqualTo("RT-2");
    }

    @Test
    void failedRefreshFallsBackToTicket() {
        AtomicInteger n = new AtomicInteger();
        handler = s -> {
            if (s.path().endsWith("/generateToken")) {
                return new Object[] { 200, tokenReply("AT-" + n.incrementAndGet(), 518400, "RT-" + n.get(), 2512799) };
            }
            if (s.path().equals(TicketProtocol.PATH_REFRESH)) {
                return new Object[] { 200, "{\"result\":false,\"error\":{\"code\":\"4010\",\"msg\":\"refreshToken无效\"}}" };
            }
            return new Object[] { 200, ADD_OK };
        };
        store.saveTicket(APP, "TK-1", clock.now);
        ticket().addPurchaseStockIn("{}");
        clock.now = clock.now.plus(Duration.ofDays(6));
        store.saveTicket(APP, "TK-2", clock.now);

        ticket().addPurchaseStockIn("{}");

        assertThat(to(TicketProtocol.PATH_REFRESH)).hasSize(1);
        List<Seen> tokenCalls = to("/generateToken");
        assertThat(tokenCalls).hasSize(2);
        assertThat(body(tokenCalls.get(1)).get("appTicket").asText()).isEqualTo("TK-2");
        assertThat(store.load(APP).accessToken()).isEqualTo("AT-2");
    }

    @Test
    void ticketExpiredReplyDropsTheTicket() {
        handler = s -> new Object[] { 200, "{\"result\":false,\"error\":{\"code\":\"4041\",\"msg\":\"appTicket已失效\"}}" };
        store.saveTicket(APP, "TK-1", clock.now);

        assertThatThrownBy(() -> ticket().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("appTicket已失效").hasMessageContaining("下一次推送");
        assertThat(store.load(APP).appTicket()).isNull();
        assertThat(to("/purchasestockin/")).isEmpty();
    }

    @Test
    void businessTokenErrorDropsStoredAccessAndRetriesOnce() {
        AtomicInteger adds = new AtomicInteger();
        handler = s -> {
            if (s.path().equals(TicketProtocol.PATH_REFRESH)) {
                return new Object[] { 200, tokenReply("AT-R", 518400, "RT", 2512799) };
            }
            // 库里那个 token 在业务接口被判失效：第一次 401，重换后的第二次成功
            return adds.incrementAndGet() == 1 ? new Object[] { 401, "{\"message\":\"token已失效\"}" }
                    : new Object[] { 200, ADD_OK };
        };
        store.saveToken(APP, "AT-dead", clock.now.plus(Duration.ofDays(1)), "RT", clock.now.plus(Duration.ofDays(20)));

        assertThat(ticket().addPurchaseStockIn("{\"code\":\"PO-1\"}")).isEqualTo("9001");

        assertThat(to("/purchasestockin/")).hasSize(2);
        assertThat(to(TicketProtocol.PATH_REFRESH)).hasSize(1);
        assertThat(store.load(APP).accessToken()).isEqualTo("AT-R");
    }

    // ---------------------------------------------------------------- 状态

    @Test
    void statusTellsWhetherWeCanSync() {
        TicketController c = controller(ticket());
        assertThat(c.chanjetStatus()).isEqualTo(new TicketController.ChanjetStatus("ticket", null, false, null, false));

        store.saveTicket(APP, "TK-1", clock.now);
        clock.now = clock.now.plus(Duration.ofMinutes(3));
        TicketController.ChanjetStatus s = c.chanjetStatus();
        assertThat(s.ticketFresh()).isTrue();
        assertThat(s.ready()).isTrue();
        assertThat(s.ticketAt()).isEqualTo(clock.now.minus(Duration.ofMinutes(3)));

        clock.now = clock.now.plus(Duration.ofMinutes(40));
        assertThat(c.chanjetStatus().ready()).isFalse();

        assertThat(controller(client(TokenMode.CERTIFICATE, "")).chanjetStatus().tokenMode()).isEqualTo("certificate");
        assertThat(controller(new FakeChanjetClient()).chanjetStatus().tokenMode()).isEqualTo("fake");
    }
}

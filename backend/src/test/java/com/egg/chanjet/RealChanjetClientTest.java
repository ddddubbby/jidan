package com.egg.chanjet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P8-4：RealChanjetClient 对一个本地假的好生意服务器（JDK 自带 HttpServer）的行为。
 * 这里的“好生意”是照 v7 7.1 手写的假答复，不是真实账套：真实的字段、路径、权限都未经实物验证，留给 H-3。
 */
class RealChanjetClientTest {

    /** 一次收到的请求。 */
    record Seen(String path, String appKey, String appSecret, String openToken, String body) {
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile Function<Seen, Object[]> handler = s -> new Object[] { 200, "{}" };
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen s = new Seen(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("appKey"),
                    ex.getRequestHeaders().getFirst("appSecret"), ex.getRequestHeaders().getFirst("openToken"), body);
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private RealChanjetClient client(String key, String secret, String book, String token, Duration timeout) {
        return new RealChanjetClient(new RealChanjetClient.Settings(url(), key, secret, book, token, timeout), json);
    }

    private RealChanjetClient ready() {
        return client("key-abc", "secret-xyz-123", "BOOK1", "token-987654", Duration.ofSeconds(5));
    }

    // ---------------------------------------------------------------- 没填密钥

    @Test
    void notConfiguredFailsBeforeAnyRequestAndNamesWhatIsMissing() {
        RealChanjetClient c = client("", null, "BOOK1", " ", Duration.ofSeconds(5));

        assertThatThrownBy(c::listVendors).isExactlyInstanceOf(ChanjetException.class)
                .hasMessageContaining("CHANJET_APP_SECRET").hasMessageContaining("CHANJET_OPEN_TOKEN")
                .hasMessageContaining("CHANJET_APP_KEY").hasMessageNotContaining("CHANJET_BOOK_ID");
        // 回传：明确没发出去，可以放心重传
        assertThatThrownBy(() -> c.addPurchaseStockIn("{}")).isInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("没有向好生意发出任何请求");
        assertThat(seen).isEmpty();
        assertThat(client("", "", "", "", Duration.ofSeconds(1)).bookName()).isEqualTo("未配置账套");
    }

    // ---------------------------------------------------------------- 回传

    @Test
    void addStockInSendsPayloadVerbatimWithHeadersAndReturnsDataId() {
        handler = s -> new Object[] { 200, "{\"success\":true,\"code\":\"openapi.e0000\",\"data\":{\"id\":\"HS-77\"}}" };
        String payload = "{\"code\":\"PO-1\",\"detailList\":[]}";

        String id = ready().addPurchaseStockIn(payload);

        assertThat(id).isEqualTo("HS-77");
        assertThat(seen).hasSize(1);
        Seen s = seen.get(0);
        assertThat(s.path()).isEqualTo("/accounting/openapi/cc/purchasestockin/add/BOOK1");
        assertThat(s.body()).isEqualTo(payload);
        assertThat(s.appKey()).isEqualTo("key-abc");
        assertThat(s.appSecret()).isEqualTo("secret-xyz-123");
        assertThat(s.openToken()).isEqualTo("token-987654");
    }

    @Test
    void addStockInRejectedWhenBusinessFailureCarriesTheirReason() {
        handler = s -> new Object[] { 200,
                "{\"success\":false,\"code\":\"openapi.e1001\",\"message\":\"供应商编码不存在\",\"data\":null}" };

        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessage("供应商编码不存在（openapi.e1001）");
    }

    @Test
    void addStockInRejectedOnHttp4xx() {
        handler = s -> new Object[] { 401, "{\"message\":\"openToken 无效或已过期\"}" };

        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("openToken 无效或已过期");
    }

    @Test
    void addStockInNoReplyWhenServerErrorOrUnreadableOrNoId() {
        handler = s -> new Object[] { 502, "<html>Bad Gateway</html>" };
        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.NoReply.class)
                .hasMessageContaining("可能已经生成");

        handler = s -> new Object[] { 200, "not json" };
        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.NoReply.class);

        handler = s -> new Object[] { 200, "{\"success\":true,\"code\":\"openapi.e0000\",\"data\":{}}" };
        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.NoReply.class)
                .hasMessageContaining("没有给出单据 ID");
    }

    @Test
    void addStockInNoReplyWhenTimeoutAndWhenConnectionRefused() {
        handler = s -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Object[] { 200, "{}" };
        };
        assertThatThrownBy(() -> client("k", "s", "B", "t", Duration.ofMillis(300)).addPurchaseStockIn("{}"))
                .isExactlyInstanceOf(ChanjetException.NoReply.class);

        int port = server.getAddress().getPort();
        server.stop(0);
        RealChanjetClient dead = new RealChanjetClient(new RealChanjetClient.Settings("http://127.0.0.1:" + port, "k", "s", "B",
                "t", Duration.ofSeconds(2)), json);
        assertThatThrownBy(() -> dead.addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.NoReply.class);
        // 同步没有“单据可能已生成”的问题，只是普通失败
        assertThatThrownBy(dead::listProducts).isExactlyInstanceOf(ChanjetException.class)
                .hasMessageNotContaining("单据");
    }

    // ---------------------------------------------------------------- 档案

    @Test
    void listsMapFieldsAndFollowPagesUntilShortPage() {
        handler = s -> {
            if (s.path().contains("/vendor/")) {
                List<String> rows = new ArrayList<>();
                int page = s.body().contains("\"pageNo\":1,") ? 1 : 2;
                int n = page == 1 ? 100 : 3;
                for (int i = 0; i < n; i++) {
                    int k = (page - 1) * 100 + i;
                    rows.add("{\"code\":\"GYS" + k + "\",\"name\":\"农户" + k + "\",\"mobile\":\"1360351" + String.format("%04d", k)
                            + "\"}");
                }
                return new Object[] { 200,
                        "{\"success\":true,\"code\":\"openapi.e0000\",\"data\":{\"list\":[" + String.join(",", rows) + "]}}" };
            }
            if (s.path().contains("/product/")) {
                return new Object[] { 200, "{\"success\":true,\"code\":\"openapi.e0000\",\"data\":["
                        + "{\"code\":\"SP1\",\"name\":\"粉蛋\",\"uomName\":\"箱\"},{\"code\":\"SP2\",\"name\":\"红蛋\"}]}" };
            }
            return new Object[] { 200, "{\"success\":true,\"code\":\"openapi.e0000\",\"data\":null}" };
        };
        RealChanjetClient c = ready();

        List<ChanjetClient.Vendor> vendors = c.listVendors();
        assertThat(vendors).hasSize(103);
        assertThat(vendors.get(0)).isEqualTo(new ChanjetClient.Vendor("GYS0", "农户0", List.of("13603510000")));
        assertThat(seen.stream().filter(s -> s.path().contains("/vendor/")).count()).isEqualTo(2);

        List<ChanjetClient.Product> products = c.listProducts();
        assertThat(products).containsExactly(new ChanjetClient.Product("SP1", "粉蛋", "箱"),
                new ChanjetClient.Product("SP2", "红蛋", "箱"));
        assertThat(c.listCustomers()).isEmpty();
        assertThat(c.listWarehouses()).isEmpty();
    }

    @Test
    void listFailureCarriesReasonAndRowWithoutCodeIsReported() {
        handler = s -> new Object[] { 200, "{\"success\":false,\"code\":\"openapi.e4003\",\"message\":\"无权限\"}" };
        assertThatThrownBy(() -> ready().listWarehouses()).isExactlyInstanceOf(ChanjetException.class)
                .hasMessageContaining("无权限");

        handler = s -> new Object[] { 200, "{\"success\":true,\"code\":\"openapi.e0000\",\"data\":[{\"name\":\"没编码\"}]}" };
        assertThatThrownBy(() -> ready().listCustomers()).hasMessageContaining("没有编码");

        handler = s -> new Object[] { 403, "{\"msg\":\"接口未开通\"}" };
        assertThatThrownBy(() -> ready().listProducts()).hasMessageContaining("HTTP 403").hasMessageContaining("接口未开通");
    }

    @Test
    void maskKeepsOnlyTheEnds() {
        assertThat(RealChanjetClient.mask("secret-xyz-123")).isEqualTo("se***23");
        assertThat(RealChanjetClient.mask("abc")).isEqualTo("***");
        assertThat(RealChanjetClient.mask(null)).isEqualTo("***");
    }
}

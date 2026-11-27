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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.egg.chanjet.RealChanjetClient.TokenMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RealChanjetClient 对一个本地假的好生意服务器（JDK 自带 HttpServer）的行为（P8-4、F3-2）。
 * 假服务器的答复照畅捷通开放平台文档里的响应示例手写（档案三个查询接口、采购入库单新增、自建应用获取 token），
 * 不是真实账套：真实的字段、权限、token 失效时的答复都未经实物验证，留给 H-3。
 */
class RealChanjetClientTest {

    /** 一次收到的请求。 */
    record Seen(String path, String appKey, String appSecret, String openToken, String body) {
    }

    /** 测试里能拨动的时钟。 */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-17T08:00:00Z");

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

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile Function<Seen, Object[]> handler = s -> new Object[] { 200, "{}" };
    private final ObjectMapper json = new ObjectMapper();
    private final TestClock clock = new TestClock();

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
        return new RealChanjetClient(new RealChanjetClient.Settings(url(), key, secret, book, token, null, TokenMode.AUTO,
                timeout), json, clock);
    }

    /** 静态 token 方式（CHANJET_OPEN_TOKEN）。 */
    private RealChanjetClient ready() {
        return client("key-abc", "secret-xyz-123", "BOOK1", "token-987654", Duration.ofSeconds(5));
    }

    /** 软证书方式（CHANJET_CERTIFICATE），按 auto 选到。 */
    private RealChanjetClient withCertificate() {
        return new RealChanjetClient(new RealChanjetClient.Settings(url(), "key-abc", "secret-xyz-123", "BOOK1", "",
                "CERT-0123456789", TokenMode.AUTO, Duration.ofSeconds(5)), json, clock);
    }

    private List<Seen> to(String pathPart) {
        return seen.stream().filter(s -> s.path().contains(pathPart)).toList();
    }

    private JsonNode body(Seen s) {
        try {
            return json.readTree(s.body());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** 文档“自建应用获取token”的响应示例，accessToken 换成可区分的值。 */
    private static String tokenReply(String token, long expiresIn) {
        return "{\"result\":true,\"value\":{\"expiresIn\":" + expiresIn + ",\"appName\":\"accounting\",\"scope\":\"auth_all\","
                + "\"accessToken\":\"" + token + "\",\"userId\":\"61000426758\",\"orgId\":\"90001203036\","
                + "\"refreshToken\":\"7fe41dfbcc8446f9a907fb36c1bdc86d\",\"refreshExpiresIn\":2512799}}";
    }

    /** 文档“采购入库单新增”的响应示例。 */
    private static final String ADD_OK = "{\"success\":true,\"code\":\"openapi.e0000\",\"message\":\"\",\"data\":{\"id\":975865062883328}}";

    // ---------------------------------------------------------------- 没填密钥

    @Test
    void notConfiguredFailsBeforeAnyRequestAndNamesWhatIsMissing() {
        RealChanjetClient c = client("", null, "BOOK1", " ", Duration.ofSeconds(5));

        assertThatThrownBy(c::listVendors).isExactlyInstanceOf(ChanjetException.class)
                .hasMessageContaining("CHANJET_APP_SECRET").hasMessageContaining("CHANJET_OPEN_TOKEN")
                .hasMessageContaining("CHANJET_CERTIFICATE").hasMessageContaining("CHANJET_APP_KEY")
                .hasMessageNotContaining("CHANJET_BOOK_ID");
        // 回传：明确没发出去，可以放心重传
        assertThatThrownBy(() -> c.addPurchaseStockIn("{}")).isInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("没有向好生意发出任何请求");
        assertThat(seen).isEmpty();
        assertThat(client("", "", "", "", Duration.ofSeconds(1)).bookName()).isEqualTo("未配置账套");
    }

    @Test
    void forcedTokenModeNamesOnlyTheValueThatModeNeeds() {
        RealChanjetClient cert = new RealChanjetClient(new RealChanjetClient.Settings(url(), "k", "s", "B", "tok", "",
                TokenMode.CERTIFICATE, Duration.ofSeconds(1)), json, clock);
        assertThatThrownBy(cert::listProducts).hasMessageContaining("缺少 CHANJET_CERTIFICATE")
                .hasMessageNotContaining("CHANJET_OPEN_TOKEN");

        RealChanjetClient stat = new RealChanjetClient(new RealChanjetClient.Settings(url(), "k", "s", "B", "", "CERT-1",
                TokenMode.STATIC, Duration.ofSeconds(1)), json, clock);
        assertThatThrownBy(stat::listProducts).hasMessageContaining("缺少 CHANJET_OPEN_TOKEN")
                .hasMessageNotContaining("CHANJET_CERTIFICATE");
        assertThat(seen).isEmpty();
    }

    @Test
    void tokenModeParsesAndRejectsTypos() {
        assertThat(TokenMode.parse(null)).isEqualTo(TokenMode.AUTO);
        assertThat(TokenMode.parse(" Certificate ")).isEqualTo(TokenMode.CERTIFICATE);
        assertThat(TokenMode.parse("static")).isEqualTo(TokenMode.STATIC);
        assertThatThrownBy(() -> TokenMode.parse("oauth")).hasMessageContaining("CHANJET_TOKEN_MODE");
    }

    @Test
    void autoUsesCertificateOnlyWhenOneIsConfigured() {
        assertThat(ready().usesCertificate()).isFalse();
        assertThat(withCertificate().usesCertificate()).isTrue();
    }

    // ---------------------------------------------------------------- 回传

    @Test
    void addStockInSendsPayloadVerbatimWithHeadersAndReturnsDataId() {
        handler = s -> new Object[] { 200, ADD_OK };
        String payload = "{\"code\":\"PO-1\",\"detailList\":[]}";

        String id = ready().addPurchaseStockIn(payload);

        assertThat(id).isEqualTo("975865062883328");
        assertThat(seen).hasSize(1);
        Seen s = seen.get(0);
        assertThat(s.path()).isEqualTo("/accounting/openapi/cc/purchasestockin/add/BOOK1");
        assertThat(s.body()).isEqualTo(payload);
        assertThat(s.appKey()).isEqualTo("key-abc");
        assertThat(s.appSecret()).isEqualTo("secret-xyz-123");
        assertThat(s.openToken()).isEqualTo("token-987654");
    }

    @Test
    void addStockInAcceptsStringSuccessAndMixedCaseCode() {
        handler = s -> new Object[] { 200, "{\"success\":\"true\",\"code\":\"openApi.e0000\",\"data\":{\"id\":42}}" };

        assertThat(ready().addPurchaseStockIn("{}")).isEqualTo("42");
    }

    @Test
    void addStockInRejectedWhenBusinessFailureCarriesTheirReason() {
        handler = s -> new Object[] { 200,
                "{\"success\":false,\"code\":\"openapi.e1001\",\"message\":\"供应商编码不存在\",\"data\":null}" };

        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessage("供应商编码不存在（openapi.e1001）");
    }

    @Test
    void rejectionReasonIncludesDetailErrorMessages() {
        // 文档里失败答复的样子：message 只写“详见详细信息”，具体原因在 data[].errorMessage
        handler = s -> new Object[] { 200, "{\"code\":\"openApi.e9000\",\"data\":[{\"errorMessage\":\"仓库编码不存在\","
                + "\"errorCode\":\"inv.e2001\",\"id\":\"x\"}],\"requestId\":\"r1\",\"success\":false,\"message\":\"其它错误,详见详细信息\"}" };

        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessage("其它错误,详见详细信息：仓库编码不存在（openApi.e9000）");
    }

    @Test
    void addStockInRejectedWhenSuccessButCodeIsNotOk() {
        handler = s -> new Object[] { 200, "{\"success\":true,\"code\":\"openapi.e2001\",\"message\":\"部分失败\",\"data\":{\"id\":1}}" };

        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("部分失败");
    }

    @Test
    void addStockInRejectedOnHttp4xxAndStaticTokenIsNotRetried() {
        handler = s -> new Object[] { 401, "{\"message\":\"openToken 无效或已过期\"}" };

        assertThatThrownBy(() -> ready().addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("openToken 无效或已过期");
        assertThat(seen).hasSize(1);
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
                "t", null, TokenMode.AUTO, Duration.ofSeconds(2)), json);
        assertThatThrownBy(() -> dead.addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.NoReply.class);
        // 同步没有“单据可能已生成”的问题，只是普通失败
        assertThatThrownBy(dead::listProducts).isExactlyInstanceOf(ChanjetException.class)
                .hasMessageNotContaining("单据");
    }

    // ---------------------------------------------------------------- openToken：软证书换取

    @Test
    void certificateIsExchangedOnceAndTheTokenIsReused() {
        handler = s -> s.path().endsWith("/generateToken") ? new Object[] { 200, tokenReply("AT-1", 518400) }
                : new Object[] { 200, ADD_OK };
        RealChanjetClient c = withCertificate();

        c.addPurchaseStockIn("{}");
        c.addPurchaseStockIn("{}");

        List<Seen> tokenCalls = to("/generateToken");
        assertThat(tokenCalls).hasSize(1);
        Seen t = tokenCalls.get(0);
        assertThat(t.path()).isEqualTo("/v1/common/auth/selfBuiltApp/generateToken");
        assertThat(t.appKey()).isEqualTo("key-abc");
        assertThat(t.appSecret()).isEqualTo("secret-xyz-123");
        assertThat(t.openToken()).isNull();
        assertThat(body(t).get("certificate").asText()).isEqualTo("CERT-0123456789");
        assertThat(body(t).has("appTicket")).isFalse();
        assertThat(to("/purchasestockin/")).extracting(Seen::openToken).containsExactly("AT-1", "AT-1");
    }

    @Test
    void tokenIsRenewedShortlyBeforeItExpires() {
        AtomicInteger n = new AtomicInteger();
        handler = s -> s.path().endsWith("/generateToken")
                ? new Object[] { 200, tokenReply("AT-" + n.incrementAndGet(), 518400) }
                : new Object[] { 200, ADD_OK };
        RealChanjetClient c = withCertificate();

        c.addPurchaseStockIn("{}");
        clock.now = clock.now.plus(Duration.ofSeconds(518400)).minus(Duration.ofMinutes(61)); // 还剩 61 分钟：照用
        c.addPurchaseStockIn("{}");
        clock.now = clock.now.plus(Duration.ofMinutes(2)); // 还剩 59 分钟：提前换
        c.addPurchaseStockIn("{}");

        assertThat(to("/generateToken")).hasSize(2);
        assertThat(to("/purchasestockin/")).extracting(Seen::openToken).containsExactly("AT-1", "AT-1", "AT-2");
    }

    @Test
    void tokenRejectedByBusinessApiIsRenewedAndTheCallRetriedOnce() {
        AtomicInteger n = new AtomicInteger();
        handler = s -> {
            if (s.path().endsWith("/generateToken")) {
                return new Object[] { 200, tokenReply("AT-" + n.incrementAndGet(), 518400) };
            }
            return "AT-1".equals(s.openToken()) ? new Object[] { 401, "{\"message\":\"token已失效\"}" }
                    : new Object[] { 200, ADD_OK };
        };

        assertThat(withCertificate().addPurchaseStockIn("{\"code\":\"PO-9\"}")).isEqualTo("975865062883328");

        assertThat(to("/generateToken")).hasSize(2);
        List<Seen> adds = to("/purchasestockin/");
        assertThat(adds).extracting(Seen::openToken).containsExactly("AT-1", "AT-2");
        assertThat(adds.get(1).body()).isEqualTo(adds.get(0).body());
    }

    @Test
    void tokenErrorInBodyOnListAlsoRetriesOnce() {
        AtomicInteger n = new AtomicInteger();
        handler = s -> {
            if (s.path().endsWith("/generateToken")) {
                return new Object[] { 200, tokenReply("AT-" + n.incrementAndGet(), 518400) };
            }
            if ("AT-1".equals(s.openToken())) {
                // 文档“Token与AppKey一致性校验”里网关答复的样子
                return new Object[] { 200, "{\"code\":50112,\"requestId\":\"r\",\"message\":\"获取token时候的appKey和当前请求appKey不一致\"}" };
            }
            return new Object[] { 200, "{\"data\":{\"count\":1,\"rows\":[{\"id\":1,\"code\":\"DEFAULT\",\"name\":\"总仓\"}],"
                    + "\"totalPage\":1},\"errCode\":\"000000\",\"errMessage\":\"000000\",\"success\":\"true\"}" };
        };

        assertThat(withCertificate().listWarehouses()).containsExactly(new ChanjetClient.Warehouse("DEFAULT", "总仓"));
        assertThat(to("/warehouse/list/")).hasSize(2);
    }

    @Test
    void secondTokenFailureIsNotRetriedAgain() {
        handler = s -> s.path().endsWith("/generateToken") ? new Object[] { 200, tokenReply("AT-x", 518400) }
                : new Object[] { 401, "{\"message\":\"token无效\"}" };

        assertThatThrownBy(() -> withCertificate().addPurchaseStockIn("{}"))
                .isExactlyInstanceOf(ChanjetException.Rejected.class).hasMessageContaining("token无效");
        assertThat(to("/purchasestockin/")).hasSize(2);
        assertThat(to("/generateToken")).hasSize(2);
    }

    @Test
    void failedTokenExchangeSendsNothingToTheBusinessApi() {
        handler = s -> new Object[] { 200, "{\"result\":false,\"error\":{\"code\":4047,\"msg\":\"请求来源IP不在白名单\",\"hint\":\"\"}}" };
        RealChanjetClient c = withCertificate();

        assertThatThrownBy(() -> c.addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("请求来源IP不在白名单（4047）").hasMessageContaining("白名单")
                .hasMessageContaining("没有受理");
        assertThatThrownBy(c::listProducts).isExactlyInstanceOf(ChanjetException.class)
                .hasMessageContaining("请求来源IP不在白名单");
        assertThat(to("/accounting/")).isEmpty();
    }

    @Test
    void appTicketRequiredFailureTellsOperatorToLowerTokenSecurity() {
        handler = s -> new Object[] { 200, "{\"result\":false,\"error\":{\"code\":4041,\"msg\":\"appTicket已失效\",\"hint\":\"\"}}" };
        RealChanjetClient c = withCertificate();

        assertThatThrownBy(c::listProducts).isExactlyInstanceOf(ChanjetException.class)
                .hasMessageContaining("appTicket已失效（4041）").hasMessageContaining("Token 安全级别")
                .hasMessageContaining("中").hasMessageContaining("低");
        assertThat(to("/accounting/")).isEmpty();
        assertThat(RealChanjetClient.tokenHint("appTicket已失效（4041）")).contains("高").contains("低");
    }

    @Test
    void tokenExchangeWithoutReplyIsStillAClearNotSent() {
        int port = server.getAddress().getPort();
        server.stop(0);
        RealChanjetClient dead = new RealChanjetClient(new RealChanjetClient.Settings("http://127.0.0.1:" + port, "k", "s", "B",
                null, "CERT-1", TokenMode.AUTO, Duration.ofSeconds(2)), json);

        // 连 token 都没换到，入库单肯定没发出去：按“明确拒绝”，可以放心重传
        assertThatThrownBy(() -> dead.addPurchaseStockIn("{}")).isExactlyInstanceOf(ChanjetException.Rejected.class)
                .hasMessageContaining("换取 openToken 时没有收到好生意的答复");
    }

    // ---------------------------------------------------------------- 档案

    @Test
    void productsFollowTotalPageAndReadBaseUnit() {
        handler = s -> {
            int page = body(s).get("pageNo").asInt();
            List<String> rows = new ArrayList<>();
            int n = page < 3 ? 20 : 5;
            for (int i = 0; i < n; i++) {
                int k = (page - 1) * 20 + i;
                String unit = k == 1 ? "" : ",\"baseUomId\":{\"id\":1051293473439969,\"uomName\":\"箱\",\"pqTypeId\":{\"id\":10301}}";
                rows.add("{\"id\":" + (1000 + k) + ",\"code\":\"SP" + k + "\",\"name\":\"蛋" + k + "\""
                        + ",\"statusEnum\":{\"value\":\"A\",\"label\":\"启用\"}" + unit + "}");
            }
            return new Object[] { 200, "{\"success\":\"true\",\"data\":{\"count\":45,\"rows\":[" + String.join(",", rows)
                    + "],\"totalPage\":3}}" };
        };

        List<ChanjetClient.Product> products = ready().listProducts();

        assertThat(products).hasSize(45);
        assertThat(products.get(0)).isEqualTo(new ChanjetClient.Product("SP0", "蛋0", "箱"));
        assertThat(products.get(1).unitName()).isEqualTo("箱"); // 没有主计量单位时按“箱”
        List<Seen> calls = to("/product/list/");
        assertThat(calls).hasSize(3);
        assertThat(calls).extracting(Seen::path).containsOnly("/accounting/openapi/cc/product/list/BOOK1");
        assertThat(calls).extracting(s -> body(s).get("pageNo").asInt()).containsExactly(1, 2, 3);
        assertThat(calls).extracting(s -> body(s).get("pageSize").asInt()).containsOnly(20);
    }

    @Test
    void vendorsAskOnlyForSupplierRolesAndCollectContactPhones() {
        handler = s -> new Object[] { 200, "{\"data\":{\"totalPage\":1,\"count\":3,\"rows\":["
                + "{\"partyCategory\":{\"code\":\"0000025\",\"name\":\"分类1\"},\"statusEnum\":{\"label\":\"启用\"},"
                + "\"code\":\"GYS0001\",\"partyName\":\"李四\",\"name\":\"李四\",\"partyRoleTypeId\":100101,"
                + "\"custVendorContactList\":[{\"contactName\":\"李四\",\"address1\":\"东村\",\"mobile\":\"13603511234\"},"
                + "{\"contactName\":\"李四家\",\"mobile\":\"13603511234\",\"telephone\":\"0354-6221234\"}],\"id\":712803436855296},"
                + "{\"code\":\"GYS0002\",\"partyName\":\"王秀英\",\"partyRoleTypeId\":100400,"
                + "\"custVendorContactList\":[{\"contactName\":\"王秀英\"}],\"id\":712803436855299},"
                + "{\"code\":\"KH0001\",\"name\":\"批发市场\",\"partyRoleTypeId\":100001}],\"curPageSize\":3},\"success\":true}" };

        List<ChanjetClient.Vendor> vendors = ready().listVendors();

        assertThat(vendors).containsExactly(
                new ChanjetClient.Vendor("GYS0001", "李四", List.of("13603511234", "0354-6221234")),
                new ChanjetClient.Vendor("GYS0002", "王秀英", List.of()));
        Seen s = to("/party/custvendor/query/").get(0);
        assertThat(s.path()).isEqualTo("/accounting/openapi/cc/party/custvendor/query/BOOK1");
        JsonNode b = body(s);
        assertThat(b.get("partyRoleTypeId").toString()).isEqualTo("[100101,100400]");
        assertThat(b.get("pageNo").asInt()).isEqualTo(1);
        assertThat(b.get("pageSize").asInt()).isEqualTo(500);
    }

    @Test
    void customersAskOnlyForCustomerRolesAndStopOnShortPageWithoutTotalPage() {
        handler = s -> {
            int page = body(s).get("pageNo").asInt();
            List<String> rows = new ArrayList<>();
            int n = page == 1 ? 500 : 2;
            for (int i = 0; i < n; i++) {
                rows.add("{\"code\":\"KH" + ((page - 1) * 500 + i) + "\",\"name\":\"客户\",\"partyRoleTypeId\":{\"id\":100001}}");
            }
            return new Object[] { 200, "{\"success\":true,\"data\":{\"rows\":[" + String.join(",", rows) + "]}}" };
        };

        assertThat(ready().listCustomers()).hasSize(502);
        List<Seen> calls = to("/party/custvendor/query/");
        assertThat(calls).hasSize(2);
        assertThat(body(calls.get(0)).get("partyRoleTypeId").toString()).isEqualTo("[100001,100400]");
    }

    @Test
    void warehousesParseTheDocumentedExample() {
        handler = s -> new Object[] { 200, "{\"data\":{\"count\":2,\"rows\":["
                + "{\"id\":3565941288469480,\"code\":\"01\",\"name\":\"1111\",\"warehouseCategoryId\":{\"id\":2965024255901696,"
                + "\"code\":\"00\",\"name\":\"未分类\"},\"statusEnum\":{\"label\":\"启用\",\"value\":\"A\"},\"isEcWarehouse\":true,"
                + "\"warehouseShippingScropList\":[]},"
                + "{\"id\":2965024255901697,\"code\":\"DEFAULT\",\"name\":\"总仓\",\"comments\":\"总仓\",\"isEcWarehouse\":false}],"
                + "\"totalPage\":1},\"errCode\":\"000000\",\"errMessage\":\"000000\",\"success\":\"true\"}" };

        assertThat(ready().listWarehouses()).containsExactly(new ChanjetClient.Warehouse("01", "1111"),
                new ChanjetClient.Warehouse("DEFAULT", "总仓"));
        Seen s = seen.get(0);
        assertThat(s.path()).isEqualTo("/accounting/openapi/cc/warehouse/list/BOOK1");
        assertThat(body(s).get("pageSize").asInt()).isEqualTo(20);
        assertThat(seen).hasSize(1);
    }

    @Test
    void emptyAccountBookGivesEmptyLists() {
        handler = s -> new Object[] { 200, s.path().contains("/warehouse/")
                ? "{\"success\":true,\"data\":{\"count\":0,\"rows\":[],\"totalPage\":0}}"
                : "{\"success\":true,\"data\":null}" };

        assertThat(ready().listWarehouses()).isEmpty();
        assertThat(ready().listCustomers()).isEmpty();
    }

    @Test
    void listFailureCarriesReasonAndRowWithoutCodeIsReported() {
        handler = s -> new Object[] { 200, "{\"success\":false,\"code\":\"openapi.e4003\",\"message\":\"无权限\"}" };
        assertThatThrownBy(() -> ready().listWarehouses()).isExactlyInstanceOf(ChanjetException.class)
                .hasMessageContaining("无权限");

        handler = s -> new Object[] { 200, "{\"success\":true,\"code\":\"openapi.e0000\",\"data\":{\"rows\":[{\"name\":\"没编码\"}]}}" };
        assertThatThrownBy(() -> ready().listCustomers()).hasMessageContaining("没有编码");

        handler = s -> new Object[] { 403, "{\"msg\":\"接口未开通\"}" };
        assertThatThrownBy(() -> ready().listProducts()).hasMessageContaining("HTTP 403").hasMessageContaining("接口未开通");

        handler = s -> new Object[] { 200, "{\"success\":true,\"code\":\"openapi.e9000\",\"data\":{\"rows\":[]}}" };
        assertThatThrownBy(() -> ready().listProducts()).hasMessageContaining("没有返回商品档案");
    }

    @Test
    void maskKeepsOnlyTheEnds() {
        assertThat(RealChanjetClient.mask("secret-xyz-123")).isEqualTo("se***23");
        assertThat(RealChanjetClient.mask("abc")).isEqualTo("***");
        assertThat(RealChanjetClient.mask(null)).isEqualTo("***");
    }
}

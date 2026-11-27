package com.egg.chanjet;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实的好生意（畅捷通开放平台）对接（开发规格 3.5、11.5）。
 *
 * <h2>未经实物验证</h2>
 * 本类照畅捷通开放平台文档写成，请求与响应的形状只对着按文档示例手写的假服务器测过（L2），
 * <b>没有接触过真实账套</b>。联调（H-3）按下面的表核对，对不上的只改本类里标出的常量与解析方法。
 * 文档站的同一棵文档树挂在“好业财|好生意 API”下（路径 {@code zplus}），两个产品共用 {@code /accounting/openapi/cc/...} 接口（v7 7.1 的说明）。
 *
 * <h2>每个接口依据的文档</h2>
 * 文档都在 https://open.chanjet.com 上；在文档路径前加 {@code /md} 可取 Markdown 原文。
 * <ol>
 * <li>新增采购入库单：{@code POST /accounting/openapi/cc/purchasestockin/add/{bookid}}，
 * 文档 /docs/file/apiFile/zplus/zjkcgl/30644?id=32738。报文由 {@link StockInPayload} 组装；
 * 成功判定照 v7 7.1：{@code success} 为真且 {@code code == "openapi.e0000"}，{@code data.id} 为单据 ID。</li>
 * <li>商品：{@code POST /accounting/openapi/cc/product/list/{bookid}}（“查询商品列表”）。请求 {@code pageNo}、{@code pageSize}
 * （文档写明最大 20）；响应 {@code data.rows[]}、{@code data.totalPage}；编码 {@code code}、名称 {@code name}、
 * 单位 {@code baseUomId.uomName}（主计量单位）。</li>
 * <li>供应商与客户：{@code POST /accounting/openapi/cc/party/custvendor/query/{bookid}}（“查询往来单位”）。
 * 请求 {@code pageNo}、{@code pageSize}（最大 1000）、{@code partyRoleTypeId}：供应商取 {@code [100101, 100400]}，
 * 客户取 {@code [100001, 100400]}（100400 是“客户/供应商”，两边都要）；响应 {@code data.rows[]}，编码 {@code code}、
 * 名称 {@code name}（没有时取 {@code partyName}）、号码取 {@code custVendorContactList[].mobile} 与 {@code telephone}。
 * 同一文档树另有 v2 的“供应商查询列表 / 客户查询列表”（{@code /cc/v2/Vendor/list}），没有用它：它的账套号走请求头
 * {@code book-id}、分页从 0 起，与入库单新增接口的 {@code {bookid}} 路径风格不一致，联系人字段里也没有手机号。</li>
 * <li>仓库：{@code POST /accounting/openapi/cc/warehouse/list/{bookid}}（“查询仓库列表”）。请求 {@code pageNo}、{@code pageSize}；
 * 响应 {@code data.rows[]}、{@code data.totalPage}，编码 {@code code}、名称 {@code name}。</li>
 * <li>openToken：自建应用用软证书换 token，{@code POST /v1/common/auth/selfBuiltApp/generateToken}
 * （文档 /docs/file/apiFile/common/selfBuiltApp/selfBuiltAppAuth），请求头 {@code appKey}、{@code appSecret}，
 * 请求体 {@code certificate}；响应 {@code result}、{@code value.accessToken}、{@code value.expiresIn}（秒，示例 518400 即 6 天）。
 * 见下文“openToken 怎么取”。</li>
 * </ol>
 *
 * <h2>文档没说清、按宽松处理的地方</h2>
 * <ul>
 * <li>档案接口的响应示例里 {@code success} 是字符串 {@code "true"}，有的示例没有 {@code code}、有的写作 {@code openApi.e0000}：
 * 档案按“{@code success} 为真（布尔或字符串），{@code code} 没有或等于 {@code openapi.e0000}（不分大小写）”判成功。</li>
 * <li>翻页：有 {@code data.totalPage} 时翻到最后一页；没有时某页不满一页就停。</li>
 * <li>四类档案都不按启用状态过滤，与改版前一致（本系统同步只增改不删）。</li>
 * <li>token 失效时开放平台返回什么，文档没有列；这里把 HTTP 401，以及 {@code success} 不为真且 {@code code} 或信息里带
 * “token”字样（不分大小写）的答复当作 token 失效（如 appKey 与 token 不一致时的 50112）。</li>
 * </ul>
 *
 * <h2>openToken 怎么取（选项 CHANJET_TOKEN_MODE）</h2>
 * 开发方无法验证、选错了回传不了，所以做成选项（开发规格第 4 章的做法），环境变量 {@code CHANJET_TOKEN_MODE}：
 * <ul>
 * <li>{@code auto}（默认）：{@code CHANJET_ENCRYPT_KEY} 与 {@code CHANJET_CERTIFICATE} 都填了按 {@code ticket}；
 * 只填了软证书按 {@code certificate}；否则按 {@code static}。</li>
 * <li>{@code ticket}：Token 安全级别“高”（附录 E P8-5）。开放平台约每 10 分钟把 appTicket 推到
 * {@code /api/chanjet/ticket}（{@link TicketController}），按 appKey 覆盖存进 {@link AuthStore}。换 token 时请求体带
 * {@code appTicket} 与 {@code certificate}；换到的 access/refresh token 也落库，重启后接着用。access 到期先用 refreshToken 续，
 * 续不上再用没过期的 appTicket 换；没有或过期的 appTicket 不发请求；答复 4041（appTicket 已失效）时丢掉它。
 * 推送报文、解密、refresh 接口的写法是推测的，集中在 {@link TicketProtocol}，未经实物验证。</li>
 * <li>{@code certificate}：自建应用的文档做法。用软证书换 token，存在内存里，剩余有效期不到十分之一（最多提前 1 小时）时重新换；
 * 调用时遇到 token 失效，重新换一次并把这次调用重发一次（只重发一次，失效答复说明请求没进业务处理，不会重复生成单据）。
 * 不落库、不排队，服务重启后重新换。对应 Token 安全级别“中”（软证书 + 服务器公网 IP 白名单）或“低”（只要软证书）。</li>
 * <li>{@code static}：直接用 {@code CHANJET_OPEN_TOKEN}，不换也不刷新，过期后换新值重启。</li>
 * </ul>
 *
 * <h2>行为约定</h2>
 * <ul>
 * <li>每次调用的原始请求与响应都打到日志（INFO），{@code appSecret}、{@code openToken}、软证书只显示前后各两位；
 * 换 token 的响应只记有效期。只打日志，不建表。</li>
 * <li>缺配置：服务照常启动；任何一次调用都在发请求之前失败，原因里列出缺哪几项。回传时这属于“明确没发出去”，
 * 按 {@link ChanjetException.Rejected} 处理，可以放心重新回传。换 token 失败同样按“明确没发出去”。</li>
 * <li>新增入库单：好生意返回了答复、{@code success} 不为真，或 HTTP 4xx，按 {@link ChanjetException.Rejected}，原因取好生意返回的信息
 * （含 {@code data[].errorMessage}）；超时、连接中断、HTTP 5xx、答复读不懂、成功但没有单据 ID，按 {@link ChanjetException.NoReply}
 * （单据可能已生成）。超时 15 秒（开发规格 3.4）。</li>
 * </ul>
 */
public class RealChanjetClient implements ChanjetClient {

    private static final Logger log = LoggerFactory.getLogger(RealChanjetClient.class);

    /** 好生意接口的地址，依据见类注释。档案与入库单后面接账套 ID。 */
    static final String PATH_ADD_STOCK_IN = "/accounting/openapi/cc/purchasestockin/add/";
    static final String PATH_PRODUCTS = "/accounting/openapi/cc/product/list/";
    static final String PATH_PARTIES = "/accounting/openapi/cc/party/custvendor/query/";
    static final String PATH_WAREHOUSES = "/accounting/openapi/cc/warehouse/list/";
    static final String PATH_TOKEN = "/v1/common/auth/selfBuiltApp/generateToken";

    /** 往来单位性质（“查询往来单位”文档）：客户、供应商、客户/供应商。 */
    static final int ROLE_CUSTOMER = 100001;
    static final int ROLE_VENDOR = 100101;
    static final int ROLE_BOTH = 100400;

    /** 每页条数：商品文档写明最大 20；往来单位最大 1000；仓库文档只写了默认 20。 */
    static final int PAGE_PRODUCTS = 20;
    static final int PAGE_PARTIES = 500;
    static final int PAGE_WAREHOUSES = 20;
    private static final int MAX_PAGES = 1000;

    private static final String OK_CODE = "openapi.e0000";
    /** token 剩余有效期不到这么多时提前换新的（再取有效期的十分之一，两者取小）。 */
    private static final Duration RENEW_BEFORE = Duration.ofHours(1);
    /** 换 token 的答复里没给有效期时，先用这么久。 */
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(1);

    /** openToken 怎么取，见类注释。 */
    public enum TokenMode {
        AUTO, TICKET, CERTIFICATE, STATIC;

        public static TokenMode parse(String s) {
            String v = s == null || s.isBlank() ? "auto" : s.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "auto" -> AUTO;
                case "ticket" -> TICKET;
                case "certificate" -> CERTIFICATE;
                case "static" -> STATIC;
                default -> throw new IllegalStateException("CHANJET_TOKEN_MODE=" + s + " 不认识，只能是 auto、ticket、certificate 或 static");
            };
        }
    }

    /** 连接配置，全部来自环境变量（开发规格 3.5）；任何一项都允许为空。 */
    public record Settings(String baseUrl, String appKey, String appSecret, String bookId, String openToken,
            String certificate, String encryptKey, TokenMode tokenMode, Duration timeout) {

        /** 不用 Token 安全级别“高”时（没有消息加解密密钥）。 */
        public Settings(String baseUrl, String appKey, String appSecret, String bookId, String openToken,
                String certificate, TokenMode tokenMode, Duration timeout) {
            this(baseUrl, appKey, appSecret, bookId, openToken, certificate, null, tokenMode, timeout);
        }
    }

    private final Settings cfg;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Clock clock;
    /** ticket 方式下的 appTicket 与 token，落库（{@link JdbcAuthStore}）。 */
    private final AuthStore store;

    /** 内存里的 openToken（只在 certificate 方式下用）；重启即丢，重新换。ticket 方式的存在 {@link #store}。 */
    private String cachedToken;
    private Instant renewAt = Instant.MIN;

    public RealChanjetClient(Settings cfg, ObjectMapper json) {
        this(cfg, json, AuthStore.memory(), Clock.systemUTC());
    }

    public RealChanjetClient(Settings cfg, ObjectMapper json, AuthStore store) {
        this(cfg, json, store, Clock.systemUTC());
    }

    RealChanjetClient(Settings cfg, ObjectMapper json, Clock clock) {
        this(cfg, json, AuthStore.memory(), clock);
    }

    RealChanjetClient(Settings cfg, ObjectMapper json, AuthStore store, Clock clock) {
        this.cfg = cfg;
        this.json = json;
        this.store = store;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(cfg.timeout()).build();
    }

    // ---------------------------------------------------------------- 档案

    @Override
    public List<Product> listProducts() {
        List<Product> out = new ArrayList<>();
        for (JsonNode n : listAll(PATH_PRODUCTS, "商品", PAGE_PRODUCTS, Map.of())) {
            out.add(new Product(required(n, "商品"), text(n, "name"), orDefault(text(n.path("baseUomId"), "uomName"), "箱")));
        }
        return out;
    }

    @Override
    public List<Vendor> listVendors() {
        List<Vendor> out = new ArrayList<>();
        for (JsonNode n : listParties("供应商", ROLE_VENDOR)) {
            List<String> phones = new ArrayList<>();
            for (JsonNode c : n.path("custVendorContactList")) {
                for (String key : List.of("mobile", "telephone")) {
                    String v = text(c, key);
                    if (v != null && !phones.contains(v)) {
                        phones.add(v);
                    }
                }
            }
            out.add(new Vendor(required(n, "供应商"), text(n, "name", "partyName"), phones));
        }
        return out;
    }

    @Override
    public List<Customer> listCustomers() {
        List<Customer> out = new ArrayList<>();
        for (JsonNode n : listParties("客户", ROLE_CUSTOMER)) {
            out.add(new Customer(required(n, "客户"), text(n, "name", "partyName")));
        }
        return out;
    }

    @Override
    public List<Warehouse> listWarehouses() {
        List<Warehouse> out = new ArrayList<>();
        for (JsonNode n : listAll(PATH_WAREHOUSES, "仓库", PAGE_WAREHOUSES, Map.of())) {
            out.add(new Warehouse(required(n, "仓库"), text(n, "name")));
        }
        return out;
    }

    /** 往来单位按性质过滤；答复里带了性质的行再核一遍，防止过滤条件没起作用时把客户当成农户。 */
    private List<JsonNode> listParties(String what, int role) {
        List<Integer> roles = List.of(role, ROLE_BOTH);
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : listAll(PATH_PARTIES, what, PAGE_PARTIES, Map.of("partyRoleTypeId", roles))) {
            JsonNode r = n.get("partyRoleTypeId");
            if (r != null && r.isObject()) {
                r = r.get("id");
            }
            if (r == null || r.isNull() || roles.contains(r.asInt())) {
                out.add(n);
            }
        }
        return out;
    }

    /** 逐页拉取：有 data.totalPage 时翻到最后一页，没有时某页不满一页就停。 */
    private List<JsonNode> listAll(String path, String what, int pageSize, Map<String, Object> filter) {
        List<JsonNode> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, Object> req = new LinkedHashMap<>(filter);
            req.put("pageNo", page);
            req.put("pageSize", pageSize);
            Reply r = call("同步" + what + "档案", path + cfg.bookId(), write(req), false);
            if (r.status() / 100 != 2) {
                throw new ChanjetException("好生意拒绝了" + what + "档案的请求（HTTP " + r.status() + "）：" + reason(r));
            }
            JsonNode root = parse(r, "同步" + what + "档案");
            if (!isSuccess(root) || !codeOk(root, true)) {
                throw new ChanjetException("好生意没有返回" + what + "档案：" + reason(r));
            }
            JsonNode data = root.path("data");
            JsonNode rows = data.get("rows");
            if (rows == null || !rows.isArray()) {
                return all;
            }
            rows.forEach(all::add);
            int totalPage = data.path("totalPage").asInt(0);
            if (rows.isEmpty() || (totalPage > 0 ? page >= totalPage : rows.size() < pageSize)) {
                return all;
            }
        }
        throw new ChanjetException("好生意的" + what + "档案超过 " + MAX_PAGES + " 页，没有同步，请联系开发方");
    }

    private static String text(JsonNode n, String... keys) {
        for (String key : keys) {
            JsonNode v = n.get(key);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return null;
    }

    private static String required(JsonNode n, String what) {
        String v = text(n, "code");
        if (v == null) {
            throw new ChanjetException("好生意返回的" + what + "档案里有一条没有编码（字段 code），字段映射需要联调时核对");
        }
        return v;
    }

    private static String orDefault(String v, String d) {
        return v == null ? d : v;
    }

    // ---------------------------------------------------------------- 回传

    @Override
    public String addPurchaseStockIn(String payload) {
        Reply r = call("回传采购入库单", PATH_ADD_STOCK_IN + cfg.bookId(), payload, true);
        if (r.status() / 100 == 5) {
            throw new ChanjetException.NoReply("好生意返回了服务器错误（HTTP " + r.status() + "），单据可能已经生成：" + reason(r));
        }
        JsonNode root;
        try {
            root = json.readTree(r.body());
        } catch (JsonProcessingException e) {
            throw new ChanjetException.NoReply("好生意的答复读不懂（HTTP " + r.status() + "），单据可能已经生成：" + abbreviate(r.body()));
        }
        if (root == null || !root.isObject()) {
            throw new ChanjetException.NoReply("好生意的答复读不懂（HTTP " + r.status() + "），单据可能已经生成：" + abbreviate(r.body()));
        }
        if (r.status() / 100 != 2 || !isSuccess(root) || !codeOk(root, false)) {
            throw new ChanjetException.Rejected(reason(r));
        }
        JsonNode id = root.path("data").get("id");
        if (id == null || id.isNull() || id.asText().isBlank()) {
            throw new ChanjetException.NoReply("好生意回复成功但没有给出单据 ID，单据可能已经生成：" + abbreviate(r.body()));
        }
        return id.asText();
    }

    @Override
    public String bookName() {
        return cfg.bookId() == null || cfg.bookId().isBlank() ? "未配置账套" : "账套 " + cfg.bookId();
    }

    // ---------------------------------------------------------------- openToken

    /** 这一次实际用哪种取法（auto 按配置推定）。 */
    TokenMode mode() {
        return switch (cfg.tokenMode() == null ? TokenMode.AUTO : cfg.tokenMode()) {
            case TICKET -> TokenMode.TICKET;
            case CERTIFICATE -> TokenMode.CERTIFICATE;
            case STATIC -> TokenMode.STATIC;
            case AUTO -> blank(cfg.certificate()) ? (blank(cfg.encryptKey()) ? TokenMode.STATIC : TokenMode.TICKET)
                    : (blank(cfg.encryptKey()) ? TokenMode.CERTIFICATE : TokenMode.TICKET);
        };
    }

    /** 这一次是换 token（软证书或 appTicket + 软证书），还是用静态 token。 */
    boolean usesCertificate() {
        return mode() != TokenMode.STATIC;
    }

    /** 换 token 失败：这一次还没有向业务接口发出任何请求。code 是开放平台给的错误码（可能没有）。 */
    private static final class TokenFailure extends RuntimeException {
        final String code;

        TokenFailure(String message) {
            this(message, null);
        }

        TokenFailure(String message, String code) {
            super(message);
            this.code = code;
        }
    }

    /** 当前可用的 openToken；换来的 token 快过期或被作废时重新换。 */
    private synchronized String openToken() {
        return switch (mode()) {
            case STATIC -> cfg.openToken();
            case TICKET -> ticketToken();
            default -> certificateToken();
        };
    }

    /** certificate 方式（安全级别“中/低”）：只用软证书换，存在内存里。 */
    private String certificateToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(renewAt)) {
            return cachedToken;
        }
        cachedToken = null;
        JsonNode value = exchange("换取 openToken", tokenRequest(Map.of("certificate", cfg.certificate())),
                "。核对 CHANJET_APP_KEY、CHANJET_APP_SECRET、CHANJET_CERTIFICATE，以及开放平台里自建应用的 Token 安全级别与 IP 白名单");
        Duration life = life(value.path("expiresIn").asLong(0));
        cachedToken = value.get("accessToken").asText();
        renewAt = now.plus(life).minus(early(life));
        return cachedToken;
    }

    /**
     * ticket 方式（安全级别“高”）：库里的 accessToken 没到期就用；到期了先用 refreshToken 续；
     * 续不上（或 refreshToken 也到期）再用开放平台最近推来的 appTicket + 软证书换。
     * 没有 appTicket 或它已过期时不发请求，直接失败。
     */
    private String ticketToken() {
        Instant now = clock.instant();
        AuthStore.State s = store.load(cfg.appKey());
        if (s.accessToken() != null && s.accessUntil() != null && now.isBefore(s.accessUntil())) {
            return s.accessToken();
        }
        if (s.refreshToken() != null && s.refreshUntil() != null && now.isBefore(s.refreshUntil())) {
            try {
                return keep(now, exchange("刷新 openToken", refreshRequest(s.refreshToken()), ""), s.refreshToken(),
                        s.refreshUntil());
            } catch (TokenFailure e) {
                log.warn("好生意 刷新 openToken 失败，改用 appTicket 重新换：{}", e.getMessage());
            }
        }
        if (s.appTicket() == null || s.ticketAt() == null) {
            throw new TokenFailure("还没有收到开放平台推送的 appTicket（Token 安全级别“高”要它）。检查开放平台里自建应用的消息接收地址"
                    + "是否指向本系统的 /api/chanjet/ticket、CHANJET_ENCRYPT_KEY 是否填对；开放平台约每 10 分钟推一次");
        }
        if (!now.isBefore(s.ticketAt().plus(TicketProtocol.TICKET_TTL))) {
            throw new TokenFailure("最近一次收到 appTicket 是 " + Duration.between(s.ticketAt(), now).toMinutes()
                    + " 分钟前，已经过期。开放平台约每 10 分钟推一次，检查消息接收地址是否还能从公网访问");
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("appTicket", s.appTicket());
        body.put("certificate", cfg.certificate());
        try {
            return keep(now, exchange("换取 openToken", tokenRequest(body),
                    "。核对 CHANJET_APP_KEY、CHANJET_APP_SECRET、CHANJET_CERTIFICATE 与开放平台推来的 appTicket"), null, null);
        } catch (TokenFailure e) {
            if (TicketProtocol.CODE_TICKET_EXPIRED.equals(e.code)) {
                store.clearTicket(cfg.appKey());
                throw new TokenFailure(e.getMessage() + "；这张 appTicket 已作废，等开放平台下一次推送（约 10 分钟）后再试", e.code);
            }
            throw e;
        }
    }

    /** 把换到的 token 落库；答复里没给新的 refreshToken 时沿用旧的。 */
    private String keep(Instant now, JsonNode value, String oldRefresh, Instant oldRefreshUntil) {
        Duration life = life(value.path("expiresIn").asLong(0));
        String access = value.get("accessToken").asText();
        String refresh = text(value, "refreshToken");
        Instant refreshUntil;
        if (refresh == null) {
            refresh = oldRefresh;
            refreshUntil = oldRefreshUntil;
        } else {
            long r = value.path("refreshExpiresIn").asLong(0);
            Duration rl = r > 0 ? Duration.ofSeconds(r) : life;
            refreshUntil = now.plus(rl).minus(early(rl));
        }
        store.saveToken(cfg.appKey(), access, now.plus(life).minus(early(life)), refresh, refreshUntil);
        return access;
    }

    private static Duration life(long seconds) {
        return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_TOKEN_TTL;
    }

    /** 提前换的余量：有效期的十分之一，最多 1 小时。 */
    private static Duration early(Duration life) {
        return life.dividedBy(10).compareTo(RENEW_BEFORE) < 0 ? life.dividedBy(10) : RENEW_BEFORE;
    }

    private HttpRequest tokenRequest(Map<String, String> body) {
        return HttpRequest.newBuilder(URI.create(base() + PATH_TOKEN)).timeout(cfg.timeout())
                .header("Content-Type", "application/json").header("appKey", cfg.appKey())
                .header("appSecret", cfg.appSecret())
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8)).build();
    }

    private HttpRequest refreshRequest(String refreshToken) {
        String q = "?grantType=refresh_token&refreshToken=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create(base() + TicketProtocol.PATH_REFRESH + q)).timeout(cfg.timeout())
                .header("appKey", cfg.appKey()).header("appSecret", cfg.appSecret()).GET().build();
    }

    /** 发一次换 token / 刷新 token 的请求，返回答复里的 value；失败抛 {@link TokenFailure}。 */
    private JsonNode exchange(String what, HttpRequest req, String hint) {
        log.info("好生意请求 {} {} {} appKey={} appSecret={} certificate={}", what, req.method(), req.uri().getPath(),
                mask(cfg.appKey()), mask(cfg.appSecret()), mask(cfg.certificate()));
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenFailure(what + " 时被中断");
        } catch (IOException | IllegalArgumentException e) {
            log.warn("好生意请求 {} 没有得到答复：{}", what, e.toString());
            throw new TokenFailure(what + " 时没有收到好生意的答复（" + describe(e) + "）");
        }
        JsonNode root = null;
        try {
            root = json.readTree(res.body());
        } catch (JsonProcessingException ignored) {
            // 落到下面
        }
        JsonNode token = root == null ? null : root.path("value").get("accessToken");
        if (res.statusCode() / 100 != 2 || root == null || !root.path("result").asBoolean(false) || token == null
                || token.asText().isBlank()) {
            log.info("好生意响应 {} HTTP {} body={}", what, res.statusCode(), res.body());
            String why = root == null ? "HTTP " + res.statusCode() + " " + abbreviate(res.body()) : tokenError(root, res);
            String code = root == null ? null : text(root.path("error"), "code");
            if (code == null && root != null) {
                code = text(root, "code");
            }
            throw new TokenFailure(what + " 失败：" + why + hint, code);
        }
        log.info("好生意响应 {} HTTP {} 取得 openToken={}，有效 {} 秒", what, res.statusCode(), mask(token.asText()),
                root.path("value").path("expiresIn").asLong(0));
        return root.path("value");
    }

    private String tokenError(JsonNode root, HttpResponse<String> res) {
        JsonNode err = root.path("error");
        String msg = text(err, "msg", "hint");
        String code = text(err, "code");
        if (msg == null) {
            msg = text(root, "message", "msg");
            code = code == null ? text(root, "code") : code;
        }
        if (msg == null) {
            return "HTTP " + res.statusCode() + " " + abbreviate(res.body());
        }
        return code == null ? msg : msg + "（" + code + "）";
    }

    /** 业务接口说 token 不能用了：丢掉缓存，下一次重新换（只丢这一次用的那个）。 */
    private synchronized void forget(String used) {
        if (used == null) {
            return;
        }
        if (used.equals(cachedToken)) {
            cachedToken = null;
        }
        if (mode() == TokenMode.TICKET && used.equals(store.load(cfg.appKey()).accessToken())) {
            store.clearAccess(cfg.appKey());
        }
    }

    /** token 失效的答复，判定见类注释“文档没说清”一节。 */
    private boolean isTokenError(Reply r) {
        if (r.status() == 401) {
            return true;
        }
        try {
            JsonNode root = json.readTree(r.body());
            if (root == null || !root.isObject() || isSuccess(root)) {
                return false;
            }
            String s = (root.path("code").asText("") + " " + root.path("message").asText("") + " "
                    + root.path("msg").asText("")).toLowerCase(Locale.ROOT);
            return s.contains("token");
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 通用

    private record Reply(int status, String body) {
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private String base() {
        return cfg.baseUrl().replaceAll("/+$", "");
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 缺哪几项就列哪几项；发请求之前就失败。 */
    private void requireConfigured(boolean forWrite) {
        List<String> missing = new ArrayList<>();
        if (blank(cfg.appKey())) {
            missing.add("CHANJET_APP_KEY");
        }
        if (blank(cfg.appSecret())) {
            missing.add("CHANJET_APP_SECRET");
        }
        if (blank(cfg.bookId())) {
            missing.add("CHANJET_BOOK_ID");
        }
        TokenMode mode = cfg.tokenMode() == null ? TokenMode.AUTO : cfg.tokenMode();
        if (mode() == TokenMode.TICKET) {
            if (blank(cfg.certificate())) {
                missing.add("CHANJET_CERTIFICATE");
            }
            if (blank(cfg.encryptKey())) {
                missing.add("CHANJET_ENCRYPT_KEY");
            }
        } else if (mode == TokenMode.CERTIFICATE && blank(cfg.certificate())) {
            missing.add("CHANJET_CERTIFICATE");
        } else if (mode == TokenMode.STATIC && blank(cfg.openToken())) {
            missing.add("CHANJET_OPEN_TOKEN");
        } else if (mode == TokenMode.AUTO && blank(cfg.certificate()) && blank(cfg.openToken())) {
            missing.add("CHANJET_CERTIFICATE（或 CHANJET_OPEN_TOKEN）");
        }
        if (blank(cfg.baseUrl())) {
            missing.add("CHANJET_BASE_URL");
        }
        if (!missing.isEmpty()) {
            fail("还没有配置好生意的连接信息，缺少 " + String.join("、", missing)
                    + "。在后端的环境变量里填好后重启服务，再重试；这一次没有向好生意发出任何请求", forWrite);
        }
    }

    private static void fail(String msg, boolean forWrite) {
        if (forWrite) {
            throw new ChanjetException.Rejected(msg);
        }
        throw new ChanjetException(msg);
    }

    /** 发一次业务请求；换来的 token（certificate、ticket 方式）遇到失效，重新换 token 再发一次。 */
    private Reply call(String what, String path, String body, boolean write) {
        requireConfigured(write);
        String token = token(write);
        Reply r = send(what, path, body, token, write);
        if (usesCertificate() && isTokenError(r)) {
            log.warn("好生意请求 {} 答复 token 失效，重新换取 openToken 后重发一次", what);
            forget(token);
            r = send(what, path, body, token(write), write);
        }
        return r;
    }

    private String token(boolean write) {
        try {
            return openToken();
        } catch (TokenFailure e) {
            fail(e.getMessage() + "；好生意这一次没有受理任何" + (write ? "入库单" : "档案请求"), write);
            return null;
        }
    }

    private Reply send(String what, String path, String body, String token, boolean write) {
        String url = base() + path;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(cfg.timeout())
                .header("Content-Type", "application/json").header("appKey", cfg.appKey())
                .header("appSecret", cfg.appSecret()).header("openToken", token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        log.info("好生意请求 {} POST {} appKey={} appSecret={} openToken={} body={}", what, url, mask(cfg.appKey()),
                mask(cfg.appSecret()), mask(token), body);
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.info("好生意响应 {} HTTP {} body={}", what, res.statusCode(), res.body());
            return new Reply(res.statusCode(), res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("好生意请求 {} 被中断", what);
            if (write) {
                throw new ChanjetException.NoReply("等待好生意答复时被中断，单据可能已经生成");
            }
            throw new ChanjetException("等待好生意答复时被中断");
        } catch (IOException | IllegalArgumentException e) {
            log.warn("好生意请求 {} 没有得到答复：{}", what, e.toString());
            String why = e instanceof HttpConnectTimeoutException
                    ? "连不上好生意（连接超时 " + cfg.timeout().toSeconds() + " 秒）"
                    : "没有收到好生意的答复（" + describe(e) + "）";
            if (write) {
                throw new ChanjetException.NoReply(why + "，单据可能已经生成");
            }
            throw new ChanjetException(why);
        }
    }

    private JsonNode parse(Reply r, String what) {
        try {
            JsonNode root = json.readTree(r.body());
            if (root == null || !root.isObject()) {
                throw new ChanjetException(what + "：好生意的答复读不懂：" + abbreviate(r.body()));
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ChanjetException(what + "：好生意的答复读不懂：" + abbreviate(r.body()));
        }
    }

    /** 文档里 success 有布尔也有字符串 "true"。 */
    private static boolean isSuccess(JsonNode root) {
        JsonNode s = root.get("success");
        return s != null && (s.asBoolean(false) || "true".equalsIgnoreCase(s.asText()));
    }

    /** code 为 openapi.e0000（文档里大小写不一）；档案接口的答复示例常常不带 code，档案允许没有。 */
    private static boolean codeOk(JsonNode root, boolean absentOk) {
        String code = text(root, "code");
        return code == null ? absentOk : OK_CODE.equalsIgnoreCase(code);
    }

    /** 给人看的原因：好生意自己写的信息优先（含 data[].errorMessage），没有就带上原始答复。 */
    private String reason(Reply r) {
        try {
            JsonNode root = json.readTree(r.body());
            if (root != null && root.isObject()) {
                String msg = text(root, "message", "msg", "errMessage", "errorMsg", "error_description", "error");
                String code = text(root, "code", "errCode", "errorCode");
                List<String> details = new ArrayList<>();
                for (JsonNode d : root.path("data")) {
                    String m = d.isObject() ? text(d, "errorMessage", "message") : null;
                    if (m != null) {
                        details.add(m);
                    }
                }
                if (!details.isEmpty()) {
                    msg = (msg == null ? "" : msg + "：") + String.join("；", details);
                }
                if (msg != null) {
                    return code == null ? msg : msg + "（" + code + "）";
                }
            }
        } catch (JsonProcessingException ignored) {
            // 落到下面
        }
        return "HTTP " + r.status() + " " + abbreviate(r.body());
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    /** 日志里不留完整密钥。 */
    static String mask(String secret) {
        if (secret == null || secret.length() <= 6) {
            return "***";
        }
        return secret.substring(0, 2) + "***" + secret.substring(secret.length() - 2);
    }
}

package com.egg.chanjet;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实的好生意（畅捷通开放平台）对接（开发规格 3.5、11.5）。
 *
 * <h2>未经实物验证</h2>
 * 本类写完没有验证过：开发方没有真实的好生意账套，也没有访问过开放平台文档站（构建环境连不上）。
 * 下面每个接口依据的文档、以及哪些地方是照 v7 7.1 写的、哪些地方只是推测，逐条写明；联调（H-3）按这张表核对，
 * 对不上的只改本类里标出的常量与解析方法。
 *
 * <h2>每个接口依据的文档</h2>
 * <ol>
 * <li>新增采购入库单：{@code POST {baseUrl}/accounting/openapi/cc/purchasestockin/add/{bookid}}，
 * 文档 https://open.chanjet.com/docs/file/apiFile/zplus/zjkcgl/30644?id=32738（“采购入库单新增”）。
 * 请求头、成功判定（{@code success == true} 且 {@code code == "openapi.e0000"}，{@code data.id} 为单据 ID）都照 v7 7.1 抄录，
 * 报文本身由 {@link StockInPayload} 组装。</li>
 * <li>四类基础档案（商品、供应商、客户、仓库）：v7 7.1 只说明它们在上一条文档的同一文档树的“基础档案”节点
 * （从上一条地址进入该文档站即可找到），没有给出各接口自己的地址，开发方也没能打开文档站。
 * <b>没有查到这四个接口的具体地址、请求参数与响应字段</b>：{@link #PATH_PRODUCTS} 等四个路径、分页参数
 * {@code pageNo / pageSize}、以及 {@link #firstArray(JsonNode)}、{@code text(...)} 里候选的字段名，都是推测的占位，
 * 必须在 H-3 对着文档与真实账套核对（v7 {@code T-03}）。</li>
 * <li>鉴权：v7 7.1 写明请求头带 {@code appKey}、{@code appSecret}、{@code openToken}；本类直接使用环境变量
 * {@code CHANJET_OPEN_TOKEN}。{@code openToken} 的获取与刷新方式没有实现（开发规格 3.5 把它列为适配点，以开放平台文档为准）：
 * 过期后换新值重启即可；要自动刷新，只在 {@link #credentials()} 里改。</li>
 * </ol>
 *
 * <h2>行为约定</h2>
 * <ul>
 * <li>每次调用的原始请求与响应都打到日志（INFO），{@code appSecret}、{@code openToken} 只显示前后各两位。只打日志，不建表。</li>
 * <li>没填 {@code CHANJET_APP_KEY / APP_SECRET / BOOK_ID / OPEN_TOKEN}：服务照常启动；任何一次调用都在发请求之前失败，
 * 原因里列出缺哪几项。回传时这属于“明确没发出去”，按 {@link ChanjetException.Rejected} 处理，可以放心重新回传。</li>
 * <li>新增入库单：好生意返回了答复、{@code success != true}，或 HTTP 4xx，按 {@link ChanjetException.Rejected}，原因取好生意返回的信息；
 * 超时、连接中断、HTTP 5xx、答复读不懂、成功但没有单据 ID，按 {@link ChanjetException.NoReply}（单据可能已生成）。超时 15 秒（开发规格 3.4）。</li>
 * </ul>
 */
public class RealChanjetClient implements ChanjetClient {

    private static final Logger log = LoggerFactory.getLogger(RealChanjetClient.class);

    /** 好生意接口的地址。<b>档案四个是推测的路径，未对照文档</b>，见类注释。 */
    static final String PATH_ADD_STOCK_IN = "/accounting/openapi/cc/purchasestockin/add/";
    static final String PATH_PRODUCTS = "/accounting/openapi/cc/product/list/";
    static final String PATH_VENDORS = "/accounting/openapi/cc/vendor/list/";
    static final String PATH_CUSTOMERS = "/accounting/openapi/cc/customer/list/";
    static final String PATH_WAREHOUSES = "/accounting/openapi/cc/warehouse/list/";

    private static final String OK_CODE = "openapi.e0000";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 200;

    /** 连接配置，全部来自环境变量（开发规格 3.5）；任何一项都允许为空。 */
    public record Settings(String baseUrl, String appKey, String appSecret, String bookId, String openToken,
            Duration timeout) {
    }

    private final Settings cfg;
    private final ObjectMapper json;
    private final HttpClient http;

    public RealChanjetClient(Settings cfg, ObjectMapper json) {
        this.cfg = cfg;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(cfg.timeout()).build();
    }

    // ---------------------------------------------------------------- 档案

    @Override
    public List<Product> listProducts() {
        List<Product> out = new ArrayList<>();
        for (JsonNode n : listAll(PATH_PRODUCTS, "商品")) {
            out.add(new Product(required(n, "商品", "code"), text(n, "name"), orDefault(text(n, "uomName", "unitName",
                    "baseUomName", "uom"), "箱")));
        }
        return out;
    }

    @Override
    public List<Vendor> listVendors() {
        List<Vendor> out = new ArrayList<>();
        for (JsonNode n : listAll(PATH_VENDORS, "供应商")) {
            List<String> phones = new ArrayList<>();
            for (String key : List.of("mobile", "phone", "contactPhone", "tel")) {
                String v = text(n, key);
                if (v != null && !phones.contains(v)) {
                    phones.add(v);
                }
            }
            out.add(new Vendor(required(n, "供应商", "code"), text(n, "name"), phones));
        }
        return out;
    }

    @Override
    public List<Customer> listCustomers() {
        List<Customer> out = new ArrayList<>();
        for (JsonNode n : listAll(PATH_CUSTOMERS, "客户")) {
            out.add(new Customer(required(n, "客户", "code"), text(n, "name")));
        }
        return out;
    }

    @Override
    public List<Warehouse> listWarehouses() {
        List<Warehouse> out = new ArrayList<>();
        for (JsonNode n : listAll(PATH_WAREHOUSES, "仓库")) {
            out.add(new Warehouse(required(n, "仓库", "code"), text(n, "name")));
        }
        return out;
    }

    /** 逐页拉取，直到某页不足一页或没有数据。没有分页的接口只会返回一页，同样适用。 */
    private List<JsonNode> listAll(String path, String what) {
        List<JsonNode> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String body = "{\"pageNo\":" + page + ",\"pageSize\":" + PAGE_SIZE + "}";
            Reply r = call("同步" + what + "档案", path, body, false);
            if (r.status() / 100 != 2) {
                throw new ChanjetException("好生意拒绝了" + what + "档案的请求（HTTP " + r.status() + "）：" + reason(r));
            }
            JsonNode root = parse(r, "同步" + what + "档案");
            if (!isOk(root)) {
                throw new ChanjetException("好生意没有返回" + what + "档案：" + reason(r));
            }
            JsonNode rows = firstArray(root.get("data"));
            if (rows == null) {
                break;
            }
            rows.forEach(all::add);
            if (rows.size() < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    /** data 本身是数组，或是带一个数组字段的对象（list / records / rows / items / data 之一）。字段名是推测，见类注释。 */
    static JsonNode firstArray(JsonNode data) {
        if (data == null || data.isNull()) {
            return null;
        }
        if (data.isArray()) {
            return data;
        }
        for (String key : List.of("list", "records", "rows", "items", "data", "dataList")) {
            JsonNode n = data.get(key);
            if (n != null && n.isArray()) {
                return n;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String... keys) {
        for (String key : keys) {
            JsonNode v = n.get(key);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return null;
    }

    private static String required(JsonNode n, String what, String key) {
        String v = text(n, key);
        if (v == null) {
            throw new ChanjetException("好生意返回的" + what + "档案里有一条没有编码（字段 " + key + "），字段映射需要联调时核对");
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
        if (r.status() / 100 != 2 || !isOk(root)) {
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

    // ---------------------------------------------------------------- 通用

    private record Reply(int status, String body) {
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
        if (blank(cfg.openToken())) {
            missing.add("CHANJET_OPEN_TOKEN");
        }
        if (blank(cfg.baseUrl())) {
            missing.add("CHANJET_BASE_URL");
        }
        if (!missing.isEmpty()) {
            String msg = "还没有配置好生意的连接信息，缺少 " + String.join("、", missing)
                    + "。在后端的环境变量里填好后重启服务，再重试；这一次没有向好生意发出任何请求";
            if (forWrite) {
                throw new ChanjetException.Rejected(msg);
            }
            throw new ChanjetException(msg);
        }
    }

    /** 密钥类的值取出来的唯一一处：以后要自动刷新 openToken，只改这里。 */
    private String[] credentials() {
        return new String[] { cfg.appKey(), cfg.appSecret(), cfg.openToken() };
    }

    private Reply call(String what, String path, String body, boolean write) {
        requireConfigured(write);
        String[] c = credentials();
        String url = cfg.baseUrl().replaceAll("/+$", "") + path;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(cfg.timeout())
                .header("Content-Type", "application/json").header("appKey", c[0]).header("appSecret", c[1])
                .header("openToken", c[2]).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        log.info("好生意请求 {} POST {} appKey={} appSecret={} openToken={} body={}", what, url, mask(c[0]), mask(c[1]), mask(c[2]),
                body);
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

    private static boolean isOk(JsonNode root) {
        return root.path("success").asBoolean(false) && OK_CODE.equals(root.path("code").asText());
    }

    /** 给人看的原因：好生意自己写的信息优先，没有就带上原始答复。 */
    private String reason(Reply r) {
        try {
            JsonNode root = json.readTree(r.body());
            if (root != null && root.isObject()) {
                String msg = text(root, "message", "msg", "errorMsg", "error_description", "error");
                String code = text(root, "code", "errorCode");
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

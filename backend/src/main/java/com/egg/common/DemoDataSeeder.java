package com.egg.common;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 演示数据（开发规格 7.3），与 lab/ 两个原型里的那套一致：李四、王秀英、张三、另一位李四，粉蛋与红蛋，
 * 三种规格，三种包装箱，v7 6.1 的规则及原型里的其余几条，几张不同状态的单据。
 * <p>
 * 只在“业务表全是空的”时写入一次，重启不会重复；账号单独判断，手机号已存在的不再建。
 * 日期都相对“今天”，所以每次在空库上启动，单据永远是“今天”和“昨天”的。
 * <p>
 * 已审核、已回传的单里冻结的均重、单价、金额与备注是照 v7 第 2、5.3 节算好的字面值，不在这里调用计价：
 * 计价只有 PricingService 一处（开发规格 2.3）。{@code DemoDataTest} 会按规则重算一遍核对这些字面值。
 */
@Component
// 先于 InitialAdmin：演示数据带了管理员时，初始管理员那一步就不会误报“没有管理员”
@Order(1)
@ConditionalOnProperty(name = "egg.seed-demo", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    /** 演示账号的密码，写在 deploy/.env.example 的注释里。只用于演示库，正式部署关掉演示数据。 */
    static final String DEMO_SCALE_PASSWORD = "scale-demo-1234";
    static final String DEMO_ADMIN_PASSWORD = "admin-demo-1234";

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Long> ids = new HashMap<>();
    private final Map<String, String> ruleNames = new HashMap<>();
    private final Map<String, BigDecimal> ruleCoefs = new HashMap<>();

    public DemoDataSeeder(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
    }

    /** @return 是否写入了数据 */
    public boolean seedIfEmpty() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!businessTablesEmpty()) {
                return false;
            }
            ids.clear();
            seed(LocalDate.now());
            return true;
        }));
    }

    private boolean businessTablesEmpty() {
        for (String t : List.of("product", "vendor", "customer", "warehouse", "spec", "tare_box",
                "pricing_rule", "purchase_order")) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class) > 0) {
                return false;
            }
        }
        return true;
    }

    private void seed(LocalDate today) {
        LocalDateTime now = LocalDateTime.now();
        seedAccounts(today);
        long admin = ids.get("a:王会计");
        seedArchive(now);
        seedBasics(today, admin);
        seedRules(admin);
        seedOrders(today, admin);
    }

    // ---------------------------------------------------------------- 账号

    private void seedAccounts(LocalDate today) {
        var enc = new BCryptPasswordEncoder();
        account("张师傅", "13900001111", DEMO_SCALE_PASSWORD, "SCALE", true, today.atTime(7, 42), enc);
        account("刘师傅", "13900003333", DEMO_SCALE_PASSWORD, "SCALE", false, today.minusDays(30).atTime(16, 10), enc);
        account("王会计", "13900002222", DEMO_ADMIN_PASSWORD, "ADMIN", true, null, enc);
    }

    private void account(String name, String phone, String password, String role, boolean enabled,
                         LocalDateTime lastLogin, BCryptPasswordEncoder enc) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM account WHERE phone = ?", Long.class, phone);
        long id = existing.isEmpty()
                ? insert("INSERT INTO account (name, phone, password_hash, role, enabled, last_login_at) VALUES (?,?,?,?,?,?)",
                        name, phone, enc.encode(password), role, enabled, lastLogin)
                : existing.get(0);
        ids.put("a:" + name, id);
    }

    // ---------------------------------------------------------------- 好生意档案

    private void seedArchive(LocalDateTime syncedAt) {
        for (String[] p : new String[][] {{"粉蛋", "SP0001"}, {"红蛋", "SP0002"}}) {
            ids.put("p:" + p[0], insert("INSERT INTO product (hs_code, name, unit_name, synced_at) VALUES (?,?,'箱',?)", p[1], p[0], syncedAt));
        }
        // 尾号 1234 对应李四与王秀英，尾号 5678 对应张三与另一位李四，用来走多人匹配（APP-02）
        vendor("f1", "李四", "GYS0001", syncedAt, "13603511234");
        vendor("f2", "王秀英", "GYS0002", syncedAt, "15803511234", "13935107788");
        vendor("f3", "张三", "GYS0003", syncedAt, "13800005678");
        vendor("f4", "李四", "GYS0004", syncedAt, "13099995678");
        for (String[] c : new String[][] {{"c1", "郑州永丰农产品批发市场", "KH0001"}, {"c2", "北京新发地蛋品行", "KH0002"}}) {
            ids.put(c[0], insert("INSERT INTO customer (hs_code, name, synced_at) VALUES (?,?,?)", c[2], c[1], syncedAt));
        }
        for (String[] w : new String[][] {{"w1", "1号仓库", "CK01"}, {"w2", "2号仓库", "CK02"}}) {
            ids.put(w[0], insert("INSERT INTO warehouse (hs_code, name, synced_at) VALUES (?,?,?)", w[2], w[1], syncedAt));
        }
    }

    private void vendor(String key, String name, String code, LocalDateTime syncedAt, String... phones) {
        long id = insert("INSERT INTO vendor (hs_code, name, synced_at) VALUES (?,?,?)", code, name, syncedAt);
        ids.put(key, id);
        for (String phone : phones) {
            insert("INSERT INTO vendor_phone (vendor_id, phone, tail4) VALUES (?,?,?)", id, phone, phone.substring(phone.length() - 4));
        }
    }

    // ---------------------------------------------------------------- 规格、皮重、基准价

    private void seedBasics(LocalDate today, long admin) {
        for (int[] s : new int[][] {{240, 60}, {360, 90}, {480, 120}}) {
            ids.put("s" + s[0], insert("INSERT INTO spec (name, egg_count, std_net_jin, enabled, updated_by) VALUES (?,?,?,1,?)",
                    s[0] + "枚/箱", s[0], s[1], admin));
        }
        tare("1号包装箱", "3", true, true, admin);
        tare("2号包装箱", "4", true, true, admin);
        tare("3号包装箱", "5", true, true, admin);
        tare("旧款木箱", "8.4", false, false, admin); // 已停用，用来看“停用”的样子
        // 昨天三种规格都有价；今天 480枚/箱 留空，走“缺基准价”
        LocalDate y = today.minusDays(1);
        price(y, "s240", "118", admin);
        price(y, "s360", "178", admin);
        price(y, "s480", "236", admin);
        price(today, "s240", "120", admin);
        price(today, "s360", "180", admin);
    }

    private void tare(String name, String jin, boolean hotkey, boolean enabled, long admin) {
        ids.put("t:" + name, insert("INSERT INTO tare_box (name, tare_jin, hotkey, enabled, updated_by) VALUES (?,?,?,?,?)",
                name, new BigDecimal(jin), hotkey, enabled, admin));
    }

    private void price(LocalDate date, String spec, String price, long admin) {
        insert("INSERT INTO base_price (price_date, spec_id, base_price, updated_by) VALUES (?,?,?,?)",
                date, ids.get(spec), new BigDecimal(price), admin);
    }

    // ---------------------------------------------------------------- 规则

    private void seedRules(long admin) {
        // v7 6.1 的李四规则是 r1；其余几条来自原型。规则不绑农户，建单时按名称选（附录 E R-1 行）。
        // 系数是元/斤；r3 演示每箱损耗调整（每箱扣 1 斤）。
        rule("r1", "粉蛋李四收购定价方案", "BUY", "粉蛋", "s360", "2号包装箱", "0", "0.5", admin);
        rule("r2", "红蛋张三收购定价方案", "BUY", "红蛋", "s360", "2号包装箱", "0", "0.75", admin);
        rule("r3", "粉蛋360通用收购方案", "BUY", "粉蛋", "s360", "2号包装箱", "-1", "0.4", admin);
        rule("r5", "粉蛋王秀英收购定价方案", "BUY", "粉蛋", "s360", "2号包装箱", "0", "0.5", admin);
        rule("r6", "红蛋王秀英小箱收购方案", "BUY", "红蛋", "s240", "1号包装箱", "0", "0.25", admin);
        rule("r7", "粉蛋大箱收购方案", "BUY", "粉蛋", "s480", "3号包装箱", "0", "0.5", admin);
        rule("d1", "永丰市场粉蛋发货方案", "SELL", "粉蛋", "s360", "2号包装箱", "0", "0.6", admin);
    }

    private void rule(String key, String name, String kind, String product, String spec, String tare, String loss,
            String coef, long admin) {
        long id = insert("INSERT INTO pricing_rule (name, kind, product_id, spec_id, tare_box_id, loss_jin, coef, enabled, updated_by)"
                        + " VALUES (?,?,?,?,?,?,?,1,?)",
                name, kind, ids.get("p:" + product), ids.get(spec), ids.get("t:" + tare), new BigDecimal(loss),
                new BigDecimal(coef), admin);
        ids.put("r:" + key, id);
        ruleNames.put(key, name);
        ruleCoefs.put(key, new BigDecimal(coef));
    }

    // ---------------------------------------------------------------- 单据

    /** 一张演示单。{@code frozen} 为空表示不冻结（待审核、已作废）。 */
    private record Demo(int dayOffset, int seq, String vendor, String product, String spec, String tare, String rule,
                        String status, String source, String operator, LocalTime at, String[] nets, int manualSeq,
                        String rawJin, String submittedPrice, String submittedAmount, Frozen frozen, String pushError) {
    }

    /** 审核时冻结的计价结果与回传备注；字面值，见类注释。 */
    private record Frozen(String avg, String price, String amount, String remark) {
    }

    private void seedOrders(LocalDate today, long admin) {
        String zhang = "张师傅", wang = "王会计";
        List<Demo> orders = new ArrayList<>();
        // 今天。第 1 张就是 v7 第 6 章的李四算例：10 箱，均重 92斤，181 元/箱，1810 元
        orders.add(new Demo(0, 1, "f1", "粉蛋", "s360", "2号包装箱", "r1", "PENDING", "APP", zhang, LocalTime.of(8, 16),
                new String[] {"93", "91.6", "92", "92", "92", "91.2", "91.2", "91.2", "91.2", "95.6"}, 0, null,
                "181", "1810", null, null));
        // 王秀英这张有一箱是手录的（秤给 60.8，手录成 58.4），审核页要显示“手录”记号
        orders.add(new Demo(0, 2, "f2", "红蛋", "s240", "1号包装箱", "r6", "PENDING", "APP", zhang, LocalTime.of(8, 51),
                new String[] {"60", "61", "59.6", "60.4", "61.2", "58.4", "60.2", "60.6"}, 6, "60.8",
                "120", "960", null, null));
        // 张三这张：APP 提交后管理员改过系数，实时重算的金额与提交时不同，审核页要标出来
        orders.add(new Demo(0, 3, "f3", "红蛋", "s360", "2号包装箱", "r2", "PENDING", "APP", zhang, LocalTime.of(9, 20),
                new String[] {"92.4", "91.6", "93.2", "92", "91.8", "92.8"}, 0, null,
                "180", "1080", null, null));
        // 已审核但回传失败：好生意明确拒绝
        orders.add(new Demo(0, 4, "f2", "粉蛋", "s360", "2号包装箱", "r5", "APPROVED", "APP", zhang, LocalTime.of(7, 58),
                new String[] {"92.2", "91.8", "92.6", "92", "91.6", "92.4", "92.2", "91.4", "92.8", "92", "91.8", "92.4"}, 0, null,
                "181", "2172",
                new Frozen("92", "181", "2172",
                        "共12箱；逐箱：92.2,91.8,92.6,92,91.6,92.4,92.2,91.4,92.8,92,91.8,92.4；"
                                + "归类：92.2斤-2箱,91.8斤-2箱,92斤-2箱,92.4斤-2箱,92.6斤-1箱,91.6斤-1箱,91.4斤-1箱,92.8斤-1箱"),
                "好生意返回：openToken 已过期（openapi.e1003）"));
        // 今天 480枚/箱 没有基准价：算不出价，提交时金额为空，Web 上标“缺基准价”
        orders.add(new Demo(0, 5, "f4", "粉蛋", "s480", "3号包装箱", "r7", "PENDING", "APP", zhang, LocalTime.of(10, 5),
                new String[] {"119.6", "120.8", "120.2"}, 0, null, null, null, null, null));
        // Web 自建后作废的单
        orders.add(new Demo(0, 6, "f1", "粉蛋", "s360", "2号包装箱", "r1", "VOID", "WEB", wang, LocalTime.of(10, 30),
                new String[] {"92", "90"}, 0, null, null, null, null, null));
        // 昨天，已回传
        orders.add(new Demo(-1, 1, "f1", "粉蛋", "s360", "2号包装箱", "r1", "PUSHED", "APP", zhang, LocalTime.of(14, 0),
                new String[] {"92.4", "91.4", "92", "91.8", "92.6", "91.2", "92.2", "92", "91.6", "92.8"}, 0, null,
                "179", "1790",
                new Frozen("92", "179", "1790",
                        "共10箱；逐箱：92.4,91.4,92,91.8,92.6,91.2,92.2,92,91.6,92.8；"
                                + "归类：92斤-2箱,92.4斤-1箱,91.4斤-1箱,91.8斤-1箱,92.6斤-1箱,91.2斤-1箱,92.2斤-1箱,91.6斤-1箱,92.8斤-1箱"),
                null));
        orders.add(new Demo(-1, 2, "f3", "红蛋", "s360", "2号包装箱", "r2", "PUSHED", "APP", zhang, LocalTime.of(15, 1),
                new String[] {"91.6", "92.2", "90.8", "93.2", "91.8", "92.4", "91.4"}, 0, null,
                "179.5", "1256.5",
                new Frozen("92", "179.5", "1256.5",
                        "共7箱；逐箱：91.6,92.2,90.8,93.2,91.8,92.4,91.4；"
                                + "归类：91.6斤-1箱,92.2斤-1箱,90.8斤-1箱,93.2斤-1箱,91.8斤-1箱,92.4斤-1箱,91.4斤-1箱"),
                null));
        orders.add(new Demo(-1, 3, "f2", "红蛋", "s240", "1号包装箱", "r6", "PUSHED", "APP", zhang, LocalTime.of(16, 2),
                new String[] {"60.4", "59.8", "60.8", "60", "59.4", "60.6", "60.2", "59.6", "61"}, 0, null,
                "118", "1062",
                new Frozen("60", "118", "1062",
                        "共9箱；逐箱：60.4,59.8,60.8,60,59.4,60.6,60.2,59.6,61；"
                                + "归类：60.4斤-1箱,59.8斤-1箱,60.8斤-1箱,60斤-1箱,59.4斤-1箱,60.6斤-1箱,60.2斤-1箱,59.6斤-1箱,61斤-1箱"),
                null));

        Map<LocalDate, Integer> lastSeq = new LinkedHashMap<>();
        for (Demo d : orders) {
            LocalDate date = today.plusDays(d.dayOffset());
            insertOrder(d, date, admin);
            lastSeq.merge(date, d.seq(), Math::max);
        }
        lastSeq.forEach((date, seq) -> jdbc.update("INSERT INTO batch_seq (biz_date, last_seq) VALUES (?,?)", date, seq));
    }

    private void insertOrder(Demo d, LocalDate date, long admin) {
        String day = date.format(COMPACT);
        String batchNo = "SG" + day + "-" + d.seq();
        String code = "CGRK" + day + String.format("%03d", d.seq());
        LocalDateTime createdAt = date.atTime(d.at());
        long operator = ids.get("a:" + d.operator());
        BigDecimal[] nets = Arrays.stream(d.nets()).map(BigDecimal::new).toArray(BigDecimal[]::new);
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal n : nets) {
            total = total.add(n);
        }
        long orderId = insert("INSERT INTO purchase_order (code, batch_no, biz_date, vendor_id, warehouse_id, product_id, spec_id, rule_id,"
                        + " source, operator_id, created_at, status, submitted_price, submitted_amount, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                code, batchNo, date, ids.get(d.vendor()), ids.get("w1"), ids.get("p:" + d.product()), ids.get(d.spec()),
                ids.get("r:" + d.rule()),
                d.source(), operator, createdAt, d.status(), dec(d.submittedPrice()), dec(d.submittedAmount()), operator);

        Frozen f = d.frozen();
        if (f != null) {
            BigDecimal stdJin = jdbc.queryForObject("SELECT std_net_jin FROM spec WHERE id = ?", BigDecimal.class, ids.get(d.spec()));
            BigDecimal base = jdbc.queryForObject("SELECT base_price FROM base_price WHERE price_date = ? AND spec_id = ?",
                    BigDecimal.class, date, ids.get(d.spec()));
            LocalDateTime approvedAt = date.atTime(d.at().plusHours(1));
            String hsId = d.status().equals("PUSHED") ? "CGRK-HSY-" + day + "-" + (30 + d.seq()) : null;
            jdbc.update("UPDATE purchase_order SET approver_id = ?, approved_at = ?, hs_id = ?, push_error = ?, push_payload = ?,"
                            + " round_mode = 'HALF', box_count = ?, total_net_jin = ?, avg_jin = ?, std_net_jin = ?, base_price = ?,"
                            + " rule_name = ?, rule_coef = ?, rule_loss_jin = 0, price = ?, amount = ? WHERE id = ?",
                    admin, approvedAt, hsId, d.pushError(), payload(d, code, date, f), nets.length, total, dec(f.avg()),
                    stdJin, base, ruleNames.get(d.rule()), ruleCoefs.get(d.rule()),
                    dec(f.price()), dec(f.amount()), orderId);
        }

        String tareName = d.tare();
        BigDecimal tareJin = jdbc.queryForObject("SELECT tare_jin FROM tare_box WHERE name = ?", BigDecimal.class, tareName);
        for (int i = 0; i < nets.length; i++) {
            int seq = i + 1;
            boolean manual = seq == d.manualSeq();
            // 手录的箱净重是人输入的，没有毛重；秤给的原始读数留在 raw_jin
            insert("INSERT INTO weigh_record (order_id, seq, net_jin, gross_jin, tare_jin, tare_name, group_no, group_k, manual, raw_jin, printed)"
                            + " VALUES (?,?,?,?,?,?,?,1,?,?,?)",
                    orderId, seq, nets[i], manual ? null : nets[i].add(tareJin), tareJin, tareName, seq,
                    manual, manual ? new BigDecimal(d.rawJin()) : null, d.source().equals("APP"));
        }
    }

    /** 审核时发给好生意的报文，字段取自 v7 7.1。正式的组装在 chanjet 包的 StockInPayload，这里只给演示库一份样子相同的。 */
    private String payload(Demo d, String code, LocalDate date, Frozen f) {
        long vendorId = ids.get(d.vendor());
        String vendorCode = jdbc.queryForObject("SELECT hs_code FROM vendor WHERE id = ?", String.class, vendorId);
        String productCode = jdbc.queryForObject("SELECT hs_code FROM product WHERE id = ?", String.class, ids.get("p:" + d.product()));
        String warehouseCode = jdbc.queryForObject("SELECT hs_code FROM warehouse WHERE id = ?", String.class, ids.get("w1"));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productCode", productCode);
        line.put("warehouseCode", warehouseCode);
        line.put("transQty", d.nets().length);
        line.put("transUomName", "箱");
        line.put("price", dec(f.price()));
        line.put("taxPct", 0);
        line.put("amount", dec(f.amount()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizDate", date.toString());
        body.put("code", code);
        body.put("redBlueFlagEnum", "BLUE");
        body.put("noteTypeEnum", "NO_INVOICE");
        body.put("custVendorCode", vendorCode);
        body.put("warehouseCode", warehouseCode);
        body.put("comments", f.remark());
        body.put("detailList", List.of(line));
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}

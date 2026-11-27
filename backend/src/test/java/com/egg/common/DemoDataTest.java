package com.egg.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.egg.EggApplication;
import com.egg.LocalMySql;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * P1-1 验收：空库启动后表齐全、演示数据在，重启不重复写入。
 * 每次 {@link #start()} 都是一次真正的应用启动（Flyway + 演示数据装载），同一个库上启动两次就是“重启”。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoDataTest {

    /** 开发规格 5.1 的 15 张表。 */
    private static final Set<String> TABLES = Set.of("account", "product", "vendor", "vendor_phone", "customer",
            "warehouse", "spec", "tare_box", "base_price", "pricing_rule", "pricing_rule_bind", "option", "batch_seq",
            "purchase_order", "weigh_record", "chanjet_auth");

    /** 本机 MySQL 上为本测试新建的空库，测完删掉。 */
    static final String DB = LocalMySql.createDatabase();

    @AfterAll
    static void dropDatabase() {
        LocalMySql.drop(DB);
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(EggApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + LocalMySql.jdbcUrl(DB),
                        "--spring.datasource.username=" + LocalMySql.USER,
                        "--spring.datasource.password=" + LocalMySql.PASSWORD,
                        "--egg.seed-demo=true");
    }

    private static Map<String, Integer> counts(JdbcTemplate jdbc) {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (String t : TABLES) {
            m.put(t, jdbc.queryForObject("SELECT COUNT(*) FROM `" + t + "`", Integer.class));
        }
        return m;
    }

    @Test
    @Order(1)
    void emptyDatabaseGetsAllTablesAndDemoData_restartAddsNothing() {
        Map<String, Integer> first;
        try (var ctx = start()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'", String.class);
            assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);

            first = counts(jdbc);
            assertThat(first).containsEntry("account", 3).containsEntry("product", 2).containsEntry("vendor", 4)
                    .containsEntry("vendor_phone", 5).containsEntry("customer", 2).containsEntry("warehouse", 2)
                    .containsEntry("spec", 3).containsEntry("tare_box", 4).containsEntry("base_price", 5)
                    .containsEntry("pricing_rule", 7).containsEntry("pricing_rule_bind", 6)
                    .containsEntry("batch_seq", 2).containsEntry("purchase_order", 9).containsEntry("weigh_record", 67)
                    // 业务设置与高级选项不预置，没存过的取默认值（P1-3）
                    .containsEntry("option", 0);
        }
        try (var ctx = start()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            assertThat(counts(jdbc)).isEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    @Order(2)
    void demoDataMatchesPrototype() {
        try (var ctx = start()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            LocalDate today = LocalDate.now();

            // 尾号 1234：李四与王秀英；尾号 5678：张三与另一位李四（APP-02 的多人匹配）
            assertThat(jdbc.queryForList("SELECT DISTINCT v.name FROM vendor v JOIN vendor_phone p ON p.vendor_id = v.id "
                    + "WHERE p.tail4 = '1234' ORDER BY v.name", String.class)).containsExactlyInAnyOrder("李四", "王秀英");
            assertThat(jdbc.queryForList("SELECT DISTINCT v.name FROM vendor v JOIN vendor_phone p ON p.vendor_id = v.id "
                    + "WHERE p.tail4 = '5678' ORDER BY v.name", String.class)).containsExactlyInAnyOrder("李四", "张三");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vendor_phone p JOIN vendor v ON v.id = p.vendor_id "
                    + "WHERE v.name = '王秀英'", Integer.class)).isEqualTo(2);

            // 当日 240、360 有基准价，480 留空
            assertThat(jdbc.queryForList("SELECT s.name FROM base_price b JOIN spec s ON s.id = b.spec_id "
                    + "WHERE b.price_date = ? ORDER BY s.egg_count", String.class, today)).containsExactly("240枚/箱", "360枚/箱");

            // 账号：密码是 BCrypt，演示密码能验过；刘师傅是停用的
            var enc = new BCryptPasswordEncoder();
            String adminHash = jdbc.queryForObject("SELECT password_hash FROM account WHERE name = '王会计' AND role = 'ADMIN'", String.class);
            assertThat(enc.matches(DemoDataSeeder.DEMO_ADMIN_PASSWORD, adminHash)).isTrue();
            String scaleHash = jdbc.queryForObject("SELECT password_hash FROM account WHERE name = '张师傅' AND role = 'SCALE'", String.class);
            assertThat(enc.matches(DemoDataSeeder.DEMO_SCALE_PASSWORD, scaleHash)).isTrue();
            assertThat(jdbc.queryForObject("SELECT enabled FROM account WHERE name = '刘师傅'", Boolean.class)).isFalse();

            // 单据四种状态都有；批次号与单据编号从批次号派生；批次序号表与最大批次号一致
            assertThat(jdbc.queryForList("SELECT DISTINCT status FROM purchase_order", String.class))
                    .containsExactlyInAnyOrder("PENDING", "APPROVED", "PUSHED", "VOID");
            String day = today.toString().replace("-", "");
            Map<String, Object> li = jdbc.queryForMap("SELECT code, batch_no, status, box_count, total_net_kg, submitted_price, submitted_amount "
                    + "FROM purchase_order WHERE batch_no = ?", "SG" + day + "-1");
            assertThat(li.get("code")).isEqualTo("CGRK" + day + "001");
            assertThat(jdbc.queryForObject("SELECT last_seq FROM batch_seq WHERE biz_date = ?", Integer.class, today)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT last_seq FROM batch_seq WHERE biz_date = ?", Integer.class, today.minusDays(1))).isEqualTo(3);

            // v7 第 6 章李四算例：10 箱共 460.5kg，提交时 181 元/箱、1810 元
            assertThat(jdbc.queryForObject("SELECT SUM(net_kg) FROM weigh_record w JOIN purchase_order o ON o.id = w.order_id "
                    + "WHERE o.batch_no = ?", BigDecimal.class, "SG" + day + "-1")).isEqualByComparingTo("460.5");
            assertThat((BigDecimal) li.get("submitted_price")).isEqualByComparingTo("181");
            assertThat((BigDecimal) li.get("submitted_amount")).isEqualByComparingTo("1810");

            // 回传失败的那张：已审核且有 push_error；手录的那箱留着原始读数
            assertThat(jdbc.queryForObject("SELECT push_error FROM purchase_order WHERE batch_no = ?", String.class, "SG" + day + "-4"))
                    .contains("openToken");
            Map<String, Object> manual = jdbc.queryForMap("SELECT net_kg, raw_kg, gross_kg FROM weigh_record w "
                    + "JOIN purchase_order o ON o.id = w.order_id WHERE o.batch_no = ? AND w.manual = 1", "SG" + day + "-2");
            assertThat((BigDecimal) manual.get("net_kg")).isEqualByComparingTo("29.2");
            assertThat((BigDecimal) manual.get("raw_kg")).isEqualByComparingTo("30.4");
            assertThat(manual.get("gross_kg")).isNull();
        }
    }

    /** 已审核、已回传的单里冻结的是字面值；这里按 v7 第 2 章公式独立重算一遍，防止字面值抄错。 */
    @Test
    @Order(3)
    void frozenFiguresAgreeWithPricingFormula() throws Exception {
        try (var ctx = start()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            var json = new ObjectMapper();
            List<Map<String, Object>> orders = jdbc.queryForList(
                    "SELECT id, batch_no, status, box_count, total_net_kg, avg_kg, std_net_kg, base_price, rule_coef, price, amount, "
                            + "round_mode, push_payload, hs_id FROM purchase_order WHERE status IN ('APPROVED','PUSHED')");
            assertThat(orders).hasSize(4);
            for (Map<String, Object> o : orders) {
                List<BigDecimal> nets = jdbc.queryForList("SELECT net_kg FROM weigh_record WHERE order_id = ? ORDER BY seq",
                        BigDecimal.class, o.get("id"));
                BigDecimal total = nets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal avg = total.divide(BigDecimal.valueOf(nets.size()), 10, RoundingMode.HALF_UP).setScale(0, RoundingMode.HALF_UP);
                BigDecimal price = ((BigDecimal) o.get("base_price"))
                        .add(((BigDecimal) o.get("rule_coef")).multiply(avg.subtract((BigDecimal) o.get("std_net_kg"))))
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal amount = price.multiply(BigDecimal.valueOf(nets.size())).setScale(2, RoundingMode.HALF_UP);
                String who = o.get("batch_no").toString();

                assertThat(o.get("round_mode")).as(who).isEqualTo("HALF");
                assertThat(o.get("box_count")).as(who).isEqualTo(nets.size());
                assertThat((BigDecimal) o.get("total_net_kg")).as(who).isEqualByComparingTo(total);
                assertThat((BigDecimal) o.get("avg_kg")).as(who).isEqualByComparingTo(avg);
                assertThat((BigDecimal) o.get("price")).as(who).isEqualByComparingTo(price);
                assertThat((BigDecimal) o.get("amount")).as(who).isEqualByComparingTo(amount);
                // 已回传有好生意单据 ID，回传失败的没有
                assertThat(o.get("hs_id") != null).as(who).isEqualTo(o.get("status").equals("PUSHED"));

                // 报文里的商品行与冻结值一致；备注的箱数与逐箱列表对得上流水
                JsonNode payload = json.readTree((String) o.get("push_payload"));
                JsonNode line = payload.at("/detailList/0");
                assertThat(line.get("transQty").asInt()).as(who).isEqualTo(nets.size());
                assertThat(line.get("price").decimalValue()).as(who).isEqualByComparingTo(price);
                assertThat(line.get("amount").decimalValue()).as(who).isEqualByComparingTo(amount);
                String remark = payload.get("comments").asText();
                List<String> each = new ArrayList<>();
                nets.forEach(n -> each.add(n.stripTrailingZeros().toPlainString()));
                assertThat(remark).as(who).startsWith("共" + nets.size() + "箱；逐箱：" + String.join(",", each) + "；归类：");
                int grouped = 0;
                for (String part : remark.substring(remark.indexOf("归类：") + 3).split(",")) {
                    grouped += Integer.parseInt(part.substring(part.indexOf("kg-") + 3, part.length() - 1));
                }
                assertThat(grouped).as(who).isEqualTo(nets.size());
            }
        }
    }

    /** 库里已有同手机号的账号（比如启动时按环境变量建好的管理员）时，只补缺的，不重复建，也不抢走已有账号。 */
    @Test
    @Order(4)
    void existingAccountIsKept_whenSeedingAnEmptiedLibrary() {
        try (var ctx = start()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            for (String t : TABLES) {
                if (!t.equals("account")) {
                    jdbc.update("TRUNCATE TABLE `" + t + "`");
                }
            }
            jdbc.update("DELETE FROM account WHERE name <> '王会计'");
            jdbc.update("UPDATE account SET name = '初始管理员' WHERE name = '王会计'");
            assertThat(ctx.getBean(DemoDataSeeder.class).seedIfEmpty()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE phone = '13900002222'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT name FROM account WHERE phone = '13900002222'", String.class)).isEqualTo("初始管理员");
            // 再来一次：业务表已有数据，什么都不做
            assertThat(ctx.getBean(DemoDataSeeder.class).seedIfEmpty()).isFalse();
        }
    }

    /** 5.1 写明的唯一约束：重复会被库拒绝。 */
    @Test
    @Order(5)
    void uniqueKeysAreEnforced() {
        try (var ctx = start()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO account (name, phone, password_hash, role) VALUES ('重复', '13900001111', 'x', 'SCALE')"))
                    .isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO vendor (hs_code, name) VALUES ('GYS0001', '重复')"))
                    .isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO base_price (price_date, spec_id, base_price) "
                    + "SELECT price_date, spec_id, 1 FROM base_price LIMIT 1")).isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO `option` (opt_key, opt_value) VALUES ('k', 'v'), ('k', 'v2')"))
                    .isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO purchase_order (code, batch_no, biz_date, vendor_id, warehouse_id, product_id, spec_id, "
                    + "source, operator_id, status) SELECT 'X1', batch_no, biz_date, vendor_id, warehouse_id, product_id, spec_id, "
                    + "source, operator_id, status FROM purchase_order LIMIT 1")).isInstanceOf(DuplicateKeyException.class);
        }
    }
}

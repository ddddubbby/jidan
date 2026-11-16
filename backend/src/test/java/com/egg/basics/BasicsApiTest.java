package com.egg.basics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.egg.MySqlTestConfig;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P1-3 验收：同一天同一规格重复录入是覆盖；设置读写一致；没存过时打印时机为逐箱、标签内容为精简、
 * 两个设备型号为当前唯一型号，设备型号存入可选值之外的值被拒；没存过的选项返回默认值，改后立即生效。
 * 不依赖登录拦截（P1-2 并行开发，尚未合入）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MySqlTestConfig.class)
class BasicsApiTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OptionService options;

    @BeforeEach
    void clean() {
        for (String t : List.of("base_price", "spec", "tare_box", "`option`")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    private ResponseEntity<JsonNode> put(String url, Object body) {
        return http.exchange(url, HttpMethod.PUT, new HttpEntity<>(body), JsonNode.class);
    }

    private JsonNode get(String url) {
        return http.getForObject(url, JsonNode.class);
    }

    private static JsonNode item(JsonNode list, String key) {
        for (JsonNode n : list.get("items")) {
            if (n.get("key").asText().equals(key)) {
                return n;
            }
        }
        throw new AssertionError("没有这个选项：" + key);
    }

    private void assertInvalid(ResponseEntity<JsonNode> res, String messagePart) {
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID");
        assertThat(res.getBody().get("message").asText()).contains(messagePart);
    }

    // ---------------- 规格 ----------------

    @Test
    void specCanBeCreatedEditedAndSwitched() {
        // v7 WEB-02 验收：把 360枚/箱 的标准净含量设为 45kg
        var created = http.postForEntity("/api/specs",
                Map.of("name", "360枚/箱", "eggCount", 360, "stdNetKg", 46), JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        long id = created.getBody().get("id").asLong();
        assertThat(created.getBody().get("enabled").asBoolean()).isTrue();

        var edited = put("/api/specs/" + id, Map.of("stdNetKg", 45));
        assertThat(edited.getBody().get("stdNetKg").decimalValue()).isEqualByComparingTo("45");
        assertThat(edited.getBody().get("name").asText()).isEqualTo("360枚/箱");

        put("/api/specs/" + id, Map.of("enabled", false));
        JsonNode list = get("/api/specs");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("enabled").asBoolean()).isFalse();
        assertThat(list.get(0).get("stdNetKg").decimalValue()).isEqualByComparingTo("45");
        assertThat(list.get(0).get("eggCount").asInt()).isEqualTo(360);
    }

    @Test
    void specInputIsValidated() {
        assertInvalid(http.postForEntity("/api/specs", Map.of("name", " ", "eggCount", 300, "stdNetKg", 37.5),
                JsonNode.class), "名称");
        assertInvalid(http.postForEntity("/api/specs", Map.of("name", "300枚/箱", "eggCount", 0, "stdNetKg", 37.5),
                JsonNode.class), "每箱枚数");
        assertInvalid(http.postForEntity("/api/specs", Map.of("name", "300枚/箱", "eggCount", 300, "stdNetKg", 0),
                JsonNode.class), "标准净含量");
        assertInvalid(http.postForEntity("/api/specs", Map.of("name", "300枚/箱", "eggCount", 300, "stdNetKg", 37.555),
                JsonNode.class), "两位小数");
        assertInvalid(put("/api/specs/999999", Map.of("enabled", true)), "不存在");
        assertThat(get("/api/specs")).isEmpty();
    }

    // ---------------- 皮重 ----------------

    @Test
    void tareCanBeCreatedEditedAndSwitched() {
        // v7 WEB-03 验收：配置“2号包装箱 = 2kg”后出现在 APP 皮重选项（热键）中
        var created = http.postForEntity("/api/tares", Map.of("name", "2号包装箱", "tareKg", 2), JsonNode.class);
        long id = created.getBody().get("id").asLong();
        assertThat(created.getBody().get("hotkey").asBoolean()).as("新增默认是热键").isTrue();
        assertThat(created.getBody().get("enabled").asBoolean()).isTrue();

        put("/api/tares/" + id, Map.of("tareKg", 2.2));
        put("/api/tares/" + id, Map.of("hotkey", false));
        JsonNode t = get("/api/tares").get(0);
        assertThat(t.get("name").asText()).isEqualTo("2号包装箱");
        assertThat(t.get("tareKg").decimalValue()).isEqualByComparingTo("2.2");
        assertThat(t.get("hotkey").asBoolean()).isFalse();

        put("/api/tares/" + id, Map.of("enabled", false, "hotkey", true));
        t = get("/api/tares").get(0);
        assertThat(t.get("enabled").asBoolean()).isFalse();
        assertThat(t.get("hotkey").asBoolean()).isTrue();

        assertInvalid(http.postForEntity("/api/tares", Map.of("name", "坏箱", "tareKg", -1), JsonNode.class), "皮重");
        assertInvalid(put("/api/tares/999999", Map.of("enabled", true)), "不存在");
    }

    // ---------------- 基准价 ----------------

    @Test
    void samePriceDateAndSpecIsOverwrittenNotDuplicated() {
        long spec = specId("360枚/箱", 360, 46);
        LocalDate d = LocalDate.of(2026, 11, 12);

        var first = put("/api/base-prices", Map.of("date", d.toString(), "specId", spec, "price", 180));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var again = put("/api/base-prices", Map.of("date", d.toString(), "specId", spec, "price", 185.5));
        assertThat(again.getBody().get("price").decimalValue()).isEqualByComparingTo("185.5");

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM base_price WHERE price_date = ? AND spec_id = ?",
                Integer.class, d, spec);
        assertThat(rows).isEqualTo(1);
        BigDecimal stored = jdbc.queryForObject("SELECT base_price FROM base_price WHERE price_date = ? AND spec_id = ?",
                BigDecimal.class, d, spec);
        assertThat(stored).isEqualByComparingTo("185.5");
    }

    @Test
    void dayListsEnabledSpecsWithBlanksAndPreviousDay() {
        long s360 = specId("360枚/箱", 360, 46);
        long s480 = specId("480枚/箱", 480, 61.5);
        long off = specId("旧规格", 100, 12);
        put("/api/specs/" + off, Map.of("enabled", false));
        put("/api/base-prices", Map.of("date", "2026-11-11", "specId", s360, "price", 178));
        put("/api/base-prices", Map.of("date", "2026-11-12", "specId", s360, "price", 180));

        JsonNode day = get("/api/base-prices?date=2026-11-12");
        assertThat(day.get("date").asText()).isEqualTo("2026-11-12");
        JsonNode items = day.get("items");
        assertThat(items).as("停用的规格不列").hasSize(2);
        assertThat(items.get(0).get("specId").asLong()).isEqualTo(s360);
        assertThat(items.get(0).get("price").decimalValue()).isEqualByComparingTo("180");
        assertThat(items.get(0).get("prevPrice").decimalValue()).isEqualByComparingTo("178");
        assertThat(items.get(0).get("stdNetKg").decimalValue()).isEqualByComparingTo("46");
        assertThat(items.get(1).get("specId").asLong()).isEqualTo(s480);
        assertThat(items.get(1).get("price").isNull()).as("没录就是 null").isTrue();
        assertThat(items.get(1).get("prevPrice").isNull()).isTrue();
    }

    @Test
    void dayDefaultsToServerToday() {
        specId("360枚/箱", 360, 46);
        assertThat(get("/api/base-prices").get("date").asText()).isEqualTo(LocalDate.now().toString());
    }

    @Test
    void basePriceInputIsValidated() {
        long spec = specId("360枚/箱", 360, 46);
        assertInvalid(put("/api/base-prices", Map.of("date", "2026-11-12", "specId", spec, "price", 0)), "基准价");
        assertInvalid(put("/api/base-prices", Map.of("date", "2026-11-12", "specId", spec, "price", 180.123)), "两位小数");
        assertInvalid(put("/api/base-prices", Map.of("date", "2026-11-12", "specId", 999999, "price", 180)), "规格不存在");
        assertInvalid(put("/api/base-prices", Map.of("specId", spec, "price", 180)), "日期");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM base_price", Integer.class)).isZero();
    }

    private long specId(String name, int eggCount, Object stdNetKg) {
        return http.postForEntity("/api/specs", Map.of("name", name, "eggCount", eggCount, "stdNetKg", stdNetKg),
                JsonNode.class).getBody().get("id").asLong();
    }

    // ---------------- 系统设置 ----------------

    @Test
    void settingsDefaultsWhenNothingStored() {
        JsonNode s = get("/api/settings");
        assertThat(s.get("items")).hasSize(10);
        assertThat(item(s, "print.timing").get("value").asText()).isEqualTo("per-box");
        assertThat(item(s, "label.content").get("value").asText()).isEqualTo("minimal");
        assertThat(item(s, "device.scale-model").get("value").asText()).isEqualTo("lanstar-ble");
        assertThat(item(s, "device.printer-model").get("value").asText()).isEqualTo("gainscha-gp-m32b");
        assertThat(item(s, "round.mode").get("value").asText()).isEqualTo("half");
        assertThat(item(s, "invoice.type").get("value").asText()).isEqualTo("NO_INVOICE");
        assertThat(item(s, "invoice.tax-rate").get("value").asText()).isEqualTo("0");
        assertThat(item(s, "label.width-mm").get("value").asText()).isEqualTo("60");
        assertThat(item(s, "label.height-mm").get("value").asText()).isEqualTo("40");
        assertThat(item(s, "label.gap-mm").get("value").asText()).isEqualTo("2");
        // 设备型号的可选值来自注册表，Web 照此画选项
        assertThat(item(s, "device.printer-model").get("choices")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `option`", Integer.class)).as("读取不写库").isZero();
    }

    @Test
    void settingsWriteThenReadBackTheSame() {
        var res = put("/api/settings", Map.of("values", Map.of(
                "round.mode", "ceil",
                "print.timing", "after-confirm",
                "invoice.type", "GENERAL",
                "invoice.tax-rate", "6",
                "label.width-mm", 50,
                "label.height-mm", "30",
                "label.gap-mm", "2.5",
                "label.content", "full")));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(item(res.getBody(), "round.mode").get("value").asText()).as("PUT 直接返回新值").isEqualTo("ceil");

        JsonNode s = get("/api/settings");
        assertThat(item(s, "round.mode").get("value").asText()).isEqualTo("ceil");
        assertThat(item(s, "print.timing").get("value").asText()).isEqualTo("after-confirm");
        assertThat(item(s, "invoice.type").get("value").asText()).isEqualTo("GENERAL");
        assertThat(item(s, "invoice.tax-rate").get("value").asText()).isEqualTo("6");
        assertThat(item(s, "label.width-mm").get("value").asText()).isEqualTo("50");
        assertThat(item(s, "label.height-mm").get("value").asText()).isEqualTo("30");
        assertThat(item(s, "label.gap-mm").get("value").asText()).isEqualTo("2.5");
        assertThat(item(s, "label.content").get("value").asText()).isEqualTo("full");
        // 没改的仍是默认
        assertThat(item(s, "device.scale-model").get("value").asText()).isEqualTo("lanstar-ble");
        // 其他模块读到的也是新值，不用重启
        assertThat(options.get("round.mode")).isEqualTo("ceil");
        assertThat(options.get("print.timing")).isEqualTo("after-confirm");
    }

    @Test
    void deviceModelOutsideChoicesIsRejectedAndNothingChanges() {
        assertInvalid(put("/api/settings", Map.of("values", Map.of("device.printer-model", "some-other-printer"))),
                "打印机型号");
        assertInvalid(put("/api/settings", Map.of("values", Map.of("device.scale-model", "some-other-scale"))),
                "电子秤型号");
        // 一批里有一项不合法，同批的其他项也不生效
        assertInvalid(put("/api/settings", Map.of("values", Map.of("round.mode", "floor",
                "device.printer-model", "nope"))), "打印机型号");
        JsonNode s = get("/api/settings");
        assertThat(item(s, "round.mode").get("value").asText()).isEqualTo("half");
        assertThat(item(s, "device.printer-model").get("value").asText()).isEqualTo("gainscha-gp-m32b");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `option`", Integer.class)).isZero();

        // 可选值之内的照常存
        assertThat(put("/api/settings", Map.of("values", Map.of("device.printer-model", "gainscha-gp-m32b")))
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void settingsRejectBadValuesAndKeysOfTheOtherPage() {
        assertInvalid(put("/api/settings", Map.of("values", Map.of("print.timing", "whenever"))), "标签打印方式");
        assertInvalid(put("/api/settings", Map.of("values", Map.of("label.content", ""))), "标签内容");
        assertInvalid(put("/api/settings", Map.of("values", Map.of("invoice.tax-rate", "abc"))), "税率");
        assertInvalid(put("/api/settings", Map.of("values", Map.of("label.width-mm", "-3"))), "标签纸宽");
        assertInvalid(put("/api/settings", Map.of("values", Map.of("no.such", "x"))), "no.such");
        // 高级选项不能从系统设置接口改，反过来也一样
        assertInvalid(put("/api/settings", Map.of("values", Map.of("print.dpi", "300"))), "print.dpi");
        assertInvalid(put("/api/options", Map.of("values", Map.of("print.timing", "per-box"))), "print.timing");
        assertInvalid(put("/api/settings", Map.of()), "没有要修改的内容");
    }

    // ---------------- 高级选项 ----------------

    @Test
    void optionsReturnDefinitionsAndDefaultsWhenNothingStored() {
        JsonNode o = get("/api/options");
        assertThat(o.get("items")).hasSize(20);
        JsonNode dpi = item(o, "print.dpi");
        assertThat(dpi.get("value").asText()).isEqualTo("203");
        assertThat(dpi.get("defaultValue").asText()).isEqualTo("203");
        assertThat(dpi.get("type").asText()).isEqualTo("INT");
        assertThat(dpi.get("group").asText()).isEqualTo("ADAPTER");
        assertThat(dpi.get("name").asText()).isNotBlank();
        assertThat(dpi.get("hint").asText()).isNotBlank();
        assertThat(item(o, "submit.without-price").get("value").asText()).isEqualTo("block");
        assertThat(item(o, "submit.without-price").get("group").asText()).isEqualTo("POLICY");
        assertThat(item(o, "submit.without-price").get("choices")).hasSize(2);
        assertThat(item(o, "push.remark").get("value").asText()).isEqualTo("full");
        assertThat(item(o, "scale.stable-window-ms").get("value").asText()).isEqualTo("800");
        assertThat(item(o, "weigh.merge-max-k").get("value").asText()).isEqualTo("8");
        // 业务设置不在高级选项里
        for (JsonNode n : o.get("items")) {
            assertThat(n.get("group").asText()).isNotEqualTo("SETTING");
        }
    }

    @Test
    void optionChangeTakesEffectImmediatelyAndCanBeReset() {
        assertThat(options.get("push.on-approve")).isEqualTo("auto");

        // 数值既可以传字符串也可以传 JSON 数字
        var res = put("/api/options", Map.of("values", Map.of(
                "push.on-approve", "manual", "scale.stable-window-ms", 1200, "scale.stable-tolerance-kg", "0.1",
                "weigh.record-by", "button")));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(options.get("push.on-approve")).as("改后立即生效，不用重启").isEqualTo("manual");
        assertThat(options.get("scale.stable-window-ms")).isEqualTo("1200");
        JsonNode o = get("/api/options");
        assertThat(item(o, "push.on-approve").get("value").asText()).isEqualTo("manual");
        assertThat(item(o, "push.on-approve").get("defaultValue").asText()).isEqualTo("auto");
        assertThat(item(o, "weigh.record-by").get("value").asText()).isEqualTo("button");
        assertThat(options.values(Options.Group.POLICY)).containsEntry("push.on-approve", "manual")
                .containsEntry("weigh.manual-entry", "on");

        // 再改一次是覆盖，不是新增一行
        put("/api/options", Map.of("values", Map.of("push.on-approve", "auto")));
        assertThat(options.get("push.on-approve")).isEqualTo("auto");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `option` WHERE opt_key = 'push.on-approve'",
                Integer.class)).isEqualTo(1);

        // 值写成 null 就是还原为默认
        var body = new java.util.HashMap<String, Object>();
        body.put("scale.stable-window-ms", null);
        put("/api/options", Map.of("values", body));
        assertThat(options.get("scale.stable-window-ms")).isEqualTo("800");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `option` WHERE opt_key = 'scale.stable-window-ms'",
                Integer.class)).isZero();
    }

    @Test
    void everyChoiceOfEveryEnumOptionCanBeSavedAndNoOtherValue() {
        // 开发规格 4.2 末：每种取值都要能走到
        for (Options.Def d : Options.of(Options.Group.ADAPTER, Options.Group.POLICY)) {
            if (d.type() != Options.Type.ENUM) {
                continue;
            }
            for (Options.Choice c : d.choices()) {
                put("/api/options", Map.of("values", Map.of(d.key(), c.value())));
                assertThat(options.get(d.key())).as(d.key()).isEqualTo(c.value());
            }
            assertInvalid(put("/api/options", Map.of("values", Map.of(d.key(), "no-such-value"))), d.name());
        }
    }

    @Test
    void optionNumbersAreValidated() {
        assertInvalid(put("/api/options", Map.of("values", Map.of("print.dpi", "abc"))), "分辨率");
        assertInvalid(put("/api/options", Map.of("values", Map.of("print.dpi", "203.5"))), "整数");
        assertInvalid(put("/api/options", Map.of("values", Map.of("weigh.merge-max-k", "0"))), "合并称重最多箱数");
        assertInvalid(put("/api/options", Map.of("values", Map.of("scale.stable-tolerance-kg", "-0.1"))), "稳定容差");
        assertThat(options.get("print.dpi")).isEqualTo("203");
    }
}

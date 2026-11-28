package com.egg.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** P2-4 验收：逐条跑通 fixtures/remark.json；另测汇总模式与重量写法。 */
class RemarkTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<BigDecimal> weights(JsonNode arr) {
        List<BigDecimal> out = new ArrayList<>();
        // 算例里的重量是 JSON 数字；按十进制文本读，免得 45.6 变成二进制小数
        arr.forEach(n -> out.add(new BigDecimal(n.asText())));
        return out;
    }

    @Test
    void everyCaseInRemarkFixture() throws IOException {
        JsonNode file = JSON.readTree(Path.of("..", "fixtures", "remark.json").toFile());
        assertThat(file.get("cases")).isNotEmpty();
        for (JsonNode c : file.get("cases")) {
            String id = c.get("id").asText();
            Remark.Result r = Remark.build(weights(c.get("input").get("boxNetJin")), c.get("input").get("maxLength").asInt());
            JsonNode want = c.get("expected");

            assertThat(r.text()).as(id + " text").isEqualTo(want.get("text").asText());
            assertThat(r.truncated()).as(id + " truncated").isEqualTo(want.get("truncated").asBoolean());
            assertThat(r.groups()).as(id + " groups").hasSize(want.get("groups").size());
            for (int i = 0; i < r.groups().size(); i++) {
                assertThat(r.groups().get(i).jin()).as(id + " groups[" + i + "].jin")
                        .isEqualByComparingTo(new BigDecimal(want.get("groups").get(i).get("jin").asText()));
                assertThat(r.groups().get(i).boxes()).as(id + " groups[" + i + "].boxes")
                        .isEqualTo(want.get("groups").get(i).get("boxes").asInt());
            }
        }
    }

    @Test
    void summaryAlwaysDropsTheEachBoxPart() {
        List<BigDecimal> nets = List.of(new BigDecimal("46.50"), new BigDecimal("46.00"), new BigDecimal("46"));
        Remark.Result r = Remark.summary(nets);
        assertThat(r.text()).isEqualTo("共3箱；归类：46斤-2箱,46.5斤-1箱");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void weightsFromTheDatabaseKeepNoTrailingZeros() {
        // 库里的重量是两位小数：46.00 与 46 归为同一组，写成 46斤；460 不写成科学计数法
        Remark.Result r = Remark.build(List.of(new BigDecimal("46.00"), new BigDecimal("460.00")), 0);
        assertThat(r.text()).isEqualTo("共2箱；逐箱：46,460；归类：46斤-1箱,460斤-1箱");
    }
}

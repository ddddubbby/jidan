package com.egg.chanjet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * F3-2：报文对照开放平台文档“采购入库单新增”的必填项与开票类型枚举；模拟好生意按同一份文档检查报文。
 * 只对照了文档，未经真实账套验证。
 */
class StockInPayloadTest {

    private final ObjectMapper json = new ObjectMapper();

    private String build(String invoiceType, String comments) {
        return StockInPayload.build(json, new StockInPayload.Input(LocalDate.of(2026, 11, 17), "CG20261117-0001", invoiceType,
                "GYS0001", "CK01", comments, "SP0001", 10, "箱", new BigDecimal("181"), BigDecimal.ZERO, new BigDecimal("1810")));
    }

    @Test
    void carriesEveryDocumentedRequiredField() throws Exception {
        JsonNode p = json.readTree(build("NO_INVOICE", "共10箱"));

        for (String k : FakeChanjetClient.REQUIRED_HEAD) {
            assertThat(p.hasNonNull(k)).as(k).isTrue();
        }
        JsonNode line = p.get("detailList").get(0);
        for (String k : FakeChanjetClient.REQUIRED_LINE) {
            assertThat(line.hasNonNull(k)).as(k).isTrue();
        }
        assertThat(p.get("redBlueFlagEnum").asText()).isEqualTo("BLUE");
        assertThat(p.has("bookid")).isFalse();
        assertThat(new FakeChanjetClient().missingFields(p.toString())).isEmpty();
    }

    @Test
    void invoiceSettingIsTranslatedToTheDocumentedEnum() throws Exception {
        assertThat(json.readTree(build("NO_INVOICE", null)).get("noteTypeEnum").asText()).isEqualTo("NO_INVOICE");
        assertThat(json.readTree(build("GENERAL", null)).get("noteTypeEnum").asText()).isEqualTo("NORMORL_INVOICE");
        assertThat(json.readTree(build("SPECIAL", null)).get("noteTypeEnum").asText()).isEqualTo("SPECIAL_INVOICE");
        assertThat(json.readTree(build("GENERAL", null)).has("comments")).isFalse();
    }

    @Test
    void fakeRejectsPayloadsThatBreakTheDocument() {
        FakeChanjetClient fake = new FakeChanjetClient();

        assertThatThrownBy(() -> fake.addPurchaseStockIn("{\"code\":\"X\",\"noteTypeEnum\":\"GENERAL\",\"detailList\":[{}]}"))
                .isExactlyInstanceOf(ChanjetException.Rejected.class).hasMessageContaining("缺少 bizDate")
                .hasMessageContaining("noteTypeEnum 取值 GENERAL").hasMessageContaining("缺少 detailList[0].productCode");
        assertThat(fake.addPurchaseStockIn(build("SPECIAL", "x"))).startsWith("FAKE-");
    }
}

package com.egg.chanjet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 采购入库单报文：全系统唯一组装的地方，只在审核时组装一次，原文存进 {@code push_payload}，
 * 之后的重新回传原样再发（开发规格 3.4、3.5）。字段映射照 v7 7.1，并已逐项对照开放平台文档“采购入库单新增”
 * （/docs/file/apiFile/zplus/zjkcgl/30644?id=32738）：表头 {@code bizDate}、{@code code}、{@code redBlueFlagEnum}、
 * {@code noteTypeEnum}、{@code custVendorCode}、{@code warehouseCode} 与明细 {@code productCode}、{@code warehouseCode}、
 * {@code taxPct}、{@code transQty}、{@code transUomName}、{@code price} 是文档里的必填项，全部带上；{@code comments}、{@code amount} 选填。
 * <p>
 * {@code bookid}：文档的请求体表格里也列了它，但请求示例里没有，其余接口都把它列为路径参数；这里照 v7 7.1 只放在路径上。
 * <p>
 * 开票类型：系统设置 {@code invoice.type} 存的是 {@code NO_INVOICE / GENERAL / SPECIAL}，组装时换成文档的枚举值
 * {@code NO_INVOICE / NORMORL_INVOICE / SPECIAL_INVOICE}（{@code NORMORL} 是文档原文的拼法，照抄）。
 * <p>
 * 未经实物验证：没有对着真实账套发过。联调时对不上只改这个文件（开发规格 11.5）。
 */
public final class StockInPayload {

    /**
     * 审核那一刻取到的全部原料。
     *
     * @param comments 备注；{@code null} 表示不带备注（选项 push.remark = off），报文里不出现这个字段
     * @param price    收购价格（元/箱）
     * @param amount   采购金额
     */
    public record Input(LocalDate bizDate, String code, String noteType, String vendorCode, String warehouseCode,
            String comments, String productCode, int boxCount, String unitName, BigDecimal price, BigDecimal taxPct,
            BigDecimal amount) {
    }

    private StockInPayload() {
    }

    /** 系统设置 invoice.type 的取值 → 文档的 noteTypeEnum；不认识的原样发，让好生意给出原因。 */
    static String noteTypeEnum(String setting) {
        if (setting == null) {
            return "NO_INVOICE";
        }
        return switch (setting) {
            case "GENERAL" -> "NORMORL_INVOICE";
            case "SPECIAL" -> "SPECIAL_INVOICE";
            default -> setting;
        };
    }

    public static String build(ObjectMapper json, Input in) {
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("bizDate", in.bizDate().toString());
        head.put("code", in.code());
        head.put("redBlueFlagEnum", "BLUE");
        head.put("noteTypeEnum", noteTypeEnum(in.noteType()));
        head.put("custVendorCode", in.vendorCode());
        head.put("warehouseCode", in.warehouseCode());
        if (in.comments() != null) {
            head.put("comments", in.comments());
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productCode", in.productCode());
        line.put("warehouseCode", in.warehouseCode());
        line.put("transQty", in.boxCount());
        line.put("transUomName", in.unitName());
        line.put("price", in.price());
        line.put("taxPct", in.taxPct());
        line.put("amount", in.amount());
        List<Object> detail = new ArrayList<>();
        detail.add(line);
        head.put("detailList", detail);
        try {
            return json.writeValueAsString(head);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("回传报文序列化失败", e);
        }
    }
}

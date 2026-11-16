package com.egg.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购入库单。一张单 = 一个批次 = 一个商品行（开发规格 5.1），所以没有明细表。
 * 待审核单不存计价结果，“审核时冻结”那组字段（roundMode 到 amount）审核前一律为 null。
 */
public class PurchaseOrder {
    public Long id;
    // 标识
    public String code;
    public String batchNo;
    // 表头
    public LocalDate bizDate;
    public Long vendorId;
    public Long warehouseId;
    public Long productId;
    public Long specId;
    public OrderSource source;
    public Long operatorId;
    public LocalDateTime createdAt;
    // 状态
    public OrderStatus status;
    public Long approverId;
    public LocalDateTime approvedAt;
    public String hsId;
    public String pushError;
    public String pushPayload;
    // 提交时的金额：APP 提交那一刻算出来的，供审核页对比
    public BigDecimal submittedPrice;
    public BigDecimal submittedAmount;
    // 审核时冻结
    public String roundMode;
    public Integer boxCount;
    public BigDecimal totalNetKg;
    public BigDecimal avgKg;
    public BigDecimal stdNetKg;
    public BigDecimal basePrice;
    public Long ruleId;
    public String ruleName;
    public BigDecimal ruleCoef;
    public BigDecimal price;
    public BigDecimal amount;
    public Long updatedBy;
    public LocalDateTime updatedAt;
}

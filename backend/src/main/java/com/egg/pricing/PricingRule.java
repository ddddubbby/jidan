package com.egg.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 定价规则。不绑农户或客户，建单时按名称直接选（附录 E R-1 行）。一条规则打包了商品、规格、包装箱（皮重）
 * 与每箱损耗调整，选了规则这几样就都定了。
 */
public class PricingRule {
    public Long id;
    public String name;
    /** BUY / SELL；建单只列收购规则 */
    public String kind;
    public Long productId;
    public Long specId;
    /** 包装箱，APP 称重时默认用它的皮重 */
    public Long tareBoxId;
    /** 每箱损耗调整（斤）：正数加、负数减，计价时加到每箱净重上 */
    public BigDecimal lossJin;
    /** 换算系数 n：每 1 斤尾差对应的每箱金额差，元/斤 */
    public BigDecimal coef;
    public Boolean enabled;
    public Long updatedBy;
    public LocalDateTime updatedAt;
}

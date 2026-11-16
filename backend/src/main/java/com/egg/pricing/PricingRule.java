package com.egg.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 定价规则。收购规则（BUY）绑农户，发货规则（SELL）绑客户；按“商品 + 规格”区分。 */
public class PricingRule {
    public Long id;
    public String name;
    /** BUY / SELL */
    public String kind;
    public Long productId;
    public Long specId;
    /** 换算系数 n：每 1kg 尾差对应的每箱金额差，元/kg */
    public BigDecimal coef;
    public Boolean enabled;
    public Long updatedBy;
    public LocalDateTime updatedAt;
}

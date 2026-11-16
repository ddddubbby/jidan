package com.egg.pricing;

/** 规则与农户（收购规则）或客户（发货规则）的绑定；联合主键（rule_id, target_id），没有自增列。 */
public class PricingRuleBind {
    public Long ruleId;
    public Long targetId;

    public PricingRuleBind() {
    }

    public PricingRuleBind(Long ruleId, Long targetId) {
        this.ruleId = ruleId;
        this.targetId = targetId;
    }
}

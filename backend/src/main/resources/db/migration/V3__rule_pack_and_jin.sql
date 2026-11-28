-- 定价规则改版与计量单位改为斤（附录 E R-1 行）。
-- 1. 规则不再绑农户/客户：建单时直接选规则。规则打包了商品、规格、包装箱皮重与每箱损耗调整。
-- 2. 全系统重量单位由 kg 改为斤：已有数据一律 ×2，换算系数（元/kg → 元/斤）×0.5。
--    已审核单的均重原是整数 kg，×2 后是偶数斤，尾差 ×2、系数 ×0.5，单价与金额不变。

-- ---------------------------------------------------------------- 规则：建单时选，打包皮重与损耗

ALTER TABLE pricing_rule
    ADD COLUMN tare_box_id BIGINT        NULL COMMENT '包装箱（皮重）；APP 称重时默认用它' AFTER spec_id,
    ADD COLUMN loss_jin    DECIMAL(6, 2) NOT NULL DEFAULT 0 COMMENT '每箱损耗调整（斤）：正数加、负数减，计价时加到每箱净重上' AFTER tare_box_id;

-- 单据的规则改为建单时选定（表头），不再只在审核时冻结。旧的待审核单按原来的绑定关系补上规则。
UPDATE purchase_order o
SET o.rule_id = (SELECT MIN(r.id) FROM pricing_rule r JOIN pricing_rule_bind b ON b.rule_id = r.id
                 WHERE b.target_id = o.vendor_id AND r.kind = 'BUY' AND r.enabled = 1
                   AND r.product_id = o.product_id AND r.spec_id = o.spec_id)
WHERE o.status = 'PENDING';

DROP TABLE pricing_rule_bind;

-- ---------------------------------------------------------------- kg → 斤

ALTER TABLE spec CHANGE std_net_kg std_net_jin DECIMAL(8, 2) NOT NULL COMMENT '标准每箱净含量（斤）';
UPDATE spec SET std_net_jin = std_net_jin * 2;

ALTER TABLE tare_box CHANGE tare_kg tare_jin DECIMAL(8, 2) NOT NULL COMMENT '皮重（斤）';
UPDATE tare_box SET tare_jin = tare_jin * 2;

ALTER TABLE pricing_rule MODIFY coef DECIMAL(8, 4) NOT NULL COMMENT '换算系数：每 1 斤尾差对应的每箱金额差，元/斤';
UPDATE pricing_rule SET coef = coef / 2;

ALTER TABLE purchase_order
    CHANGE total_net_kg total_net_jin DECIMAL(8, 2) NULL,
    CHANGE avg_kg avg_jin DECIMAL(8, 2) NULL,
    CHANGE std_net_kg std_net_jin DECIMAL(8, 2) NULL,
    ADD COLUMN rule_loss_jin DECIMAL(6, 2) NULL COMMENT '审核时冻结：每箱损耗调整（斤）' AFTER rule_coef;
UPDATE purchase_order
SET total_net_jin = total_net_jin * 2,
    avg_jin       = avg_jin * 2,
    std_net_jin   = std_net_jin * 2,
    rule_coef     = rule_coef / 2,
    rule_loss_jin = CASE WHEN rule_coef IS NULL THEN NULL ELSE 0 END;

ALTER TABLE weigh_record
    CHANGE net_kg net_jin DECIMAL(8, 2) NOT NULL,
    CHANGE gross_kg gross_jin DECIMAL(8, 2) NULL,
    CHANGE tare_kg tare_jin DECIMAL(8, 2) NOT NULL,
    CHANGE raw_kg raw_jin DECIMAL(8, 2) NULL COMMENT '手录前秤给出的净重（斤）；手工加的箱为空';
UPDATE weigh_record
SET net_jin   = net_jin * 2,
    gross_jin = gross_jin * 2,
    tare_jin  = tare_jin * 2,
    raw_jin   = raw_jin * 2;

-- 以 kg 为单位的两个高级选项改名并换算
UPDATE `option` SET opt_key = 'weigh.empty-below-jin', opt_value = TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(CAST(opt_value AS DECIMAL(10, 4)) * 2 AS CHAR)))
WHERE opt_key = 'weigh.empty-below-kg';
UPDATE `option` SET opt_key = 'scale.stable-tolerance-jin', opt_value = TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(CAST(opt_value AS DECIMAL(10, 4)) * 2 AS CHAR)))
WHERE opt_key = 'scale.stable-tolerance-kg';

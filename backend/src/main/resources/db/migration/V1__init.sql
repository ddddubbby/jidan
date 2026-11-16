-- 开发规格 5.1 的全部表。已发布后不再修改本脚本，改表一律新增 V2、V3……（开发规格 2.7）。
-- 不建外键：规则唯一匹配等约束按 5.1 在 Service 里检查，档案只增改不删，引用关系靠程序保证。
-- 重量 DECIMAL(8,2) 单位 kg，金额 DECIMAL(12,2) 单位元。换算系数 coef 规格未写精度，取 (8,4)，够写 0.125 这类系数。
-- 配置类表与单据表带 updated_by、updated_at：只记最后修改人与时间（v7 第 9 章第 4 条）。
-- 状态、角色、取整方式存英文常量：status 为 PENDING/APPROVED/PUSHED/VOID，role 为 SCALE/ADMIN，
-- source 为 APP/WEB，kind 为 BUY/SELL，round_mode 为 HALF/FLOOR/CEIL（四舍五入/抹零/进位）。

CREATE TABLE account (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(10)  NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    token         VARCHAR(64)  NULL,
    last_login_at DATETIME     NULL,
    updated_by    BIGINT       NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_phone (phone),
    KEY idx_account_token (token)
) DEFAULT CHARSET = utf8mb4 COMMENT = '磅秤员与管理员';

CREATE TABLE product (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    hs_code   VARCHAR(50)  NOT NULL,
    name      VARCHAR(100) NOT NULL,
    unit_name VARCHAR(20)  NOT NULL DEFAULT '箱',
    synced_at DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_hs_code (hs_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = '好生意商品';

CREATE TABLE vendor (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    hs_code   VARCHAR(50)  NOT NULL,
    name      VARCHAR(100) NOT NULL,
    synced_at DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vendor_hs_code (hs_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = '好生意供应商，即农户';

CREATE TABLE vendor_phone (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    vendor_id BIGINT      NOT NULL,
    phone     VARCHAR(30) NOT NULL,
    tail4     VARCHAR(4)  NOT NULL COMMENT '手机号后四位，供尾号检索',
    PRIMARY KEY (id),
    KEY idx_vendor_phone_vendor (vendor_id),
    KEY idx_vendor_phone_tail4 (tail4)
) DEFAULT CHARSET = utf8mb4 COMMENT = '农户的号码，一个农户可有多个';

CREATE TABLE customer (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    hs_code   VARCHAR(50)  NOT NULL,
    name      VARCHAR(100) NOT NULL,
    synced_at DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_hs_code (hs_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = '好生意客户';

CREATE TABLE warehouse (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    hs_code   VARCHAR(50)  NOT NULL,
    name      VARCHAR(100) NOT NULL,
    synced_at DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_hs_code (hs_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = '好生意仓库';

CREATE TABLE spec (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50)   NOT NULL,
    egg_count  INT           NOT NULL,
    std_net_kg DECIMAL(8, 2) NOT NULL COMMENT '标准每箱净含量',
    enabled    TINYINT(1)    NOT NULL DEFAULT 1,
    updated_by BIGINT        NULL,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) DEFAULT CHARSET = utf8mb4 COMMENT = '包装规格与标准净含量';

CREATE TABLE tare_box (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50)   NOT NULL,
    tare_kg    DECIMAL(8, 2) NOT NULL,
    hotkey     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '称重页是否显示为热键',
    enabled    TINYINT(1)    NOT NULL DEFAULT 1,
    updated_by BIGINT        NULL,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) DEFAULT CHARSET = utf8mb4 COMMENT = '包装箱皮重与热键';

CREATE TABLE base_price (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    price_date DATE           NOT NULL,
    spec_id    BIGINT         NOT NULL,
    base_price DECIMAL(12, 2) NOT NULL,
    updated_by BIGINT         NULL,
    updated_at DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_base_price_date_spec (price_date, spec_id)
) DEFAULT CHARSET = utf8mb4 COMMENT = '每规格每日一条的市场基准价';

CREATE TABLE pricing_rule (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100)  NOT NULL,
    kind       VARCHAR(10)   NOT NULL COMMENT 'BUY 收购规则 / SELL 发货规则',
    product_id BIGINT        NOT NULL,
    spec_id    BIGINT        NOT NULL,
    coef       DECIMAL(8, 4) NOT NULL COMMENT '换算系数：每 1kg 尾差对应的每箱金额差，元/kg',
    enabled    TINYINT(1)    NOT NULL DEFAULT 1,
    updated_by BIGINT        NULL,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) DEFAULT CHARSET = utf8mb4 COMMENT = '定价规则';

CREATE TABLE pricing_rule_bind (
    rule_id   BIGINT NOT NULL,
    target_id BIGINT NOT NULL COMMENT '收购规则是农户 id，发货规则是客户 id',
    PRIMARY KEY (rule_id, target_id),
    KEY idx_rule_bind_target (target_id)
) DEFAULT CHARSET = utf8mb4 COMMENT = '规则与农户、客户的绑定';

CREATE TABLE `option` (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    opt_key    VARCHAR(80)  NOT NULL,
    opt_value  VARCHAR(500) NOT NULL,
    updated_by BIGINT       NULL,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_option_key (opt_key)
) DEFAULT CHARSET = utf8mb4 COMMENT = '业务设置与高级选项的值；定义与默认值在代码里的注册表，没存过的取默认值';

CREATE TABLE batch_seq (
    biz_date DATE NOT NULL,
    last_seq INT  NOT NULL,
    PRIMARY KEY (biz_date)
) DEFAULT CHARSET = utf8mb4 COMMENT = '批次号当日序号';

CREATE TABLE purchase_order (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    -- 标识
    code             VARCHAR(30)    NOT NULL COMMENT '单据编号，从批次号派生',
    batch_no         VARCHAR(30)    NOT NULL,
    -- 表头
    biz_date         DATE           NOT NULL COMMENT '收购日期',
    vendor_id        BIGINT         NOT NULL,
    warehouse_id     BIGINT         NOT NULL,
    product_id       BIGINT         NOT NULL,
    spec_id          BIGINT         NOT NULL,
    source           VARCHAR(10)    NOT NULL COMMENT 'APP / WEB',
    operator_id      BIGINT         NOT NULL,
    created_at       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 状态
    status           VARCHAR(10)    NOT NULL COMMENT 'PENDING / APPROVED / PUSHED / VOID',
    approver_id      BIGINT         NULL,
    approved_at      DATETIME       NULL,
    hs_id            VARCHAR(64)    NULL COMMENT '好生意返回的单据 ID',
    push_error       TEXT           NULL COMMENT 'APPROVED 且有值即回传失败',
    push_payload     MEDIUMTEXT     NULL COMMENT '审核时发给好生意的报文原文，重新回传原样再发',
    -- 提交时的金额：APP 提交那一刻算出来的，供审核页对比
    submitted_price  DECIMAL(12, 2) NULL,
    submitted_amount DECIMAL(12, 2) NULL,
    -- 审核时冻结；退回待审核时清空
    round_mode       VARCHAR(10)    NULL,
    box_count        INT            NULL,
    total_net_kg     DECIMAL(8, 2)  NULL,
    avg_kg           DECIMAL(8, 2)  NULL,
    std_net_kg       DECIMAL(8, 2)  NULL,
    base_price       DECIMAL(12, 2) NULL,
    rule_id          BIGINT         NULL,
    rule_name        VARCHAR(100)   NULL,
    rule_coef        DECIMAL(8, 4)  NULL,
    price            DECIMAL(12, 2) NULL,
    amount           DECIMAL(12, 2) NULL,
    updated_by       BIGINT         NULL,
    updated_at       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_code (code),
    UNIQUE KEY uk_order_batch_no (batch_no),
    KEY idx_order_status_date (status, biz_date),
    KEY idx_order_vendor (vendor_id)
) DEFAULT CHARSET = utf8mb4 COMMENT = '采购入库单；一张单 = 一个批次 = 一个商品行';

CREATE TABLE weigh_record (
    id        BIGINT        NOT NULL AUTO_INCREMENT,
    order_id  BIGINT        NOT NULL,
    seq       INT           NOT NULL COMMENT '批次序号，从 1 起',
    net_kg    DECIMAL(8, 2) NOT NULL,
    gross_kg  DECIMAL(8, 2) NULL,
    tare_kg   DECIMAL(8, 2) NOT NULL,
    tare_name VARCHAR(50)   NOT NULL,
    group_no  INT           NOT NULL COMMENT '称重组号：同一次合并称重的流水共用',
    group_k   INT           NOT NULL COMMENT '本次称重箱数 k',
    manual    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '手录',
    raw_kg    DECIMAL(8, 2) NULL COMMENT '手录前秤给出的净重；手工加的箱为空',
    printed   TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_weigh_order_seq (order_id, seq)
) DEFAULT CHARSET = utf8mb4 COMMENT = '逐箱流水';

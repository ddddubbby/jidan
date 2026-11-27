-- Token 安全级别“高”（CHANJET_TOKEN_MODE=ticket）的换票状态（附录 E P8-5）。
-- 每个 appKey 一行、只覆盖：开放平台推来的最新 appTicket，与用它换到的 access/refresh token。
-- 不是业务缓存：只为重启后不必等下一次推送、推送地址断了还能靠 refreshToken 续期。
-- *_until 是“用到这个时刻为止”，已扣掉提前换的余量。

CREATE TABLE chanjet_auth (
    app_key       VARCHAR(100) NOT NULL,
    app_ticket    VARCHAR(500) NULL,
    ticket_at     DATETIME(3)  NULL,
    access_token  VARCHAR(500) NULL,
    access_until  DATETIME(3)  NULL,
    refresh_token VARCHAR(500) NULL,
    refresh_until DATETIME(3)  NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (app_key)
) DEFAULT CHARSET = utf8mb4 COMMENT = '好生意 Token 安全级别“高”的 appTicket 与 token，每个 appKey 一行';

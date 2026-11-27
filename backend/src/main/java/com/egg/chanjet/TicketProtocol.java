package com.egg.chanjet;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Token 安全级别“高”用到的、<b>还没有对照原文核对过</b>的开放平台约定，全部集中在这一个类里（附录 E P8-5）。
 *
 * <p>开发方写这部分时打不开开放平台文档站，也拿不到参考实现的源码，下面每一项都是按参考实现的结构说明推测的。
 * 联调（H-3）时对照开放平台“消息推送 / 自建应用 appTicket / 刷新 token”文档逐项核对，对不上只改这里：
 * <ol>
 * <li>推送报文外层是 JSON，密文放在 {@link #FIELD_ENCRYPT_MSG}；</li>
 * <li>密文是 Base64，解密用 {@link #CIPHER}，密钥是开放平台给自建应用的“消息加解密密钥”原文的 UTF-8 字节；</li>
 * <li>明文是 JSON：消息类型 {@link #FIELD_MSG_TYPE}、应用 {@link #FIELD_APP_KEY}、appTicket 在
 * {@link #FIELD_BIZ_CONTENT}.{@link #FIELD_APP_TICKET}；</li>
 * <li>消息类型 {@link #MSG_APP_TICKET}（带 appTicket）与 {@link #MSG_APP_TEST}（配置消息地址时的连通测试）；</li>
 * <li>收到后回 {@link #REPLY_OK}，平台据此认为推送成功；</li>
 * <li>appTicket 有效期按 {@link #TICKET_TTL}（参考实现说明“约每 10 分钟推一次”，有效期没写明）；</li>
 * <li>appTicket 失效时换 token 的错误码 {@link #CODE_TICKET_EXPIRED}（真实账套上见过“4041 appTicket已失效”）；</li>
 * <li>刷新 token：{@code GET} {@link #PATH_REFRESH}，查询参数 {@code grantType=refresh_token&refreshToken=…}，
 * 请求头带 {@code appKey}、{@code appSecret}，答复与“自建应用获取token”同形（{@code result}、{@code value.accessToken} 等）。</li>
 * </ol>
 * 验证级别：只在模拟环境里用测试自己生成的密钥加解密过，<b>未经实物验证</b>。
 */
final class TicketProtocol {

    private TicketProtocol() {
    }

    static final String FIELD_ENCRYPT_MSG = "encryptMsg";
    static final String CIPHER = "AES/ECB/PKCS5Padding";
    static final String FIELD_MSG_TYPE = "msgType";
    static final String FIELD_APP_KEY = "appKey";
    static final String FIELD_BIZ_CONTENT = "bizContent";
    static final String FIELD_APP_TICKET = "appTicket";
    static final String MSG_APP_TICKET = "APP_TICKET";
    static final String MSG_APP_TEST = "APP_TEST";
    static final String REPLY_OK = "{\"result\":\"success\"}";
    static final Duration TICKET_TTL = Duration.ofMinutes(30);
    static final String CODE_TICKET_EXPIRED = "4041";
    static final String PATH_REFRESH = "/auth/v2/refreshToken";

    /** 解开推送的密文；密钥或密文不对时抛 {@link GeneralSecurityException} 或 {@link IllegalArgumentException}。 */
    static String decrypt(String base64, String key) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(CIPHER);
        c.init(Cipher.DECRYPT_MODE, keySpec(key));
        return new String(c.doFinal(Base64.getDecoder().decode(base64.trim())), StandardCharsets.UTF_8);
    }

    /** 与 {@link #decrypt} 相反，测试里模拟开放平台推送用。 */
    static String encrypt(String plain, String key) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(CIPHER);
        c.init(Cipher.ENCRYPT_MODE, keySpec(key));
        return Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static SecretKeySpec keySpec(String key) {
        return new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
    }
}

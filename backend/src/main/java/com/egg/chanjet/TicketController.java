package com.egg.chanjet;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;

import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token 安全级别“高”（附录 E P8-5）：
 * <ul>
 * <li>{@code POST /api/chanjet/ticket}：开放平台的消息接收地址。不用登录；能用 CHANJET_ENCRYPT_KEY 解开、
 * 且 appKey 与本系统的一致，才当作开放平台发来的。{@code APP_TICKET} 覆盖保存，其他消息类型（含连通测试
 * {@code APP_TEST}）只记日志。报文格式见 {@link TicketProtocol}（待核对）。</li>
 * <li>{@code GET /api/chanjet/status}：给 Web 显示“appTicket 最近收到”与能不能同步。</li>
 * </ul>
 */
@RestController
public class TicketController {

    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

    /**
     * @param tokenMode   fake（模拟好生意）/ ticket / certificate / static
     * @param ticketAt    最近一次收到 appTicket 的时间；没收到过为空（只在 ticket 方式下有）
     * @param ticketFresh 最近的 appTicket 还没过期
     * @param tokenUntil  库里的 openToken 用到什么时候；没有为空（只在 ticket 方式下有）
     * @param ready       现在能不能向好生意发请求；ticket 方式下要么有没到期的 token，要么有没过期的 appTicket
     */
    public record ChanjetStatus(String tokenMode, Instant ticketAt, boolean ticketFresh, Instant tokenUntil, boolean ready) {
    }

    private final ChanjetClient chanjet;
    private final AuthStore store;
    private final ObjectMapper json;
    private final String appKey;
    private final String encryptKey;
    private final Clock clock;

    @Autowired
    public TicketController(ChanjetClient chanjet, AuthStore store, ObjectMapper json,
            @Value("${egg.chanjet.app-key:}") String appKey, @Value("${egg.chanjet.encrypt-key:}") String encryptKey) {
        this(chanjet, store, json, appKey, encryptKey, Clock.systemUTC());
    }

    TicketController(ChanjetClient chanjet, AuthStore store, ObjectMapper json, String appKey, String encryptKey,
            Clock clock) {
        this.chanjet = chanjet;
        this.store = store;
        this.json = json;
        this.appKey = appKey;
        this.encryptKey = encryptKey;
        this.clock = clock;
    }

    @Access(Who.PUBLIC)
    @PostMapping(value = "/api/chanjet/ticket", produces = MediaType.APPLICATION_JSON_VALUE)
    public String receiveTicket(@RequestBody(required = false) String body) {
        if (encryptKey == null || encryptKey.isBlank()) {
            log.warn("好生意推送 收到一条消息，但没有配置 CHANJET_ENCRYPT_KEY，解不开");
            throw ApiException.invalid("没有配置 CHANJET_ENCRYPT_KEY");
        }
        JsonNode msg;
        try {
            JsonNode outer = json.readTree(body == null ? "" : body);
            JsonNode cipher = outer == null ? null : outer.get(TicketProtocol.FIELD_ENCRYPT_MSG);
            if (cipher == null || !cipher.isTextual()) {
                throw new IllegalArgumentException("没有 " + TicketProtocol.FIELD_ENCRYPT_MSG);
            }
            msg = json.readTree(TicketProtocol.decrypt(cipher.asText(), encryptKey));
            if (msg == null || !msg.isObject()) {
                throw new IllegalArgumentException("解开后不是 JSON 对象");
            }
        } catch (GeneralSecurityException | IllegalArgumentException | JsonProcessingException e) {
            log.warn("好生意推送 解不开：{}；原文前 200 字 {}", e.toString(), abbreviate(body));
            throw ApiException.invalid("消息解不开，核对 CHANJET_ENCRYPT_KEY 与开放平台的消息加解密密钥是否一致");
        }
        String type = msg.path(TicketProtocol.FIELD_MSG_TYPE).asText("");
        String from = msg.path(TicketProtocol.FIELD_APP_KEY).asText("");
        if (!from.equals(appKey)) {
            log.warn("好生意推送 {} 来自 appKey={}，与本系统的 CHANJET_APP_KEY 不一致，不处理", type,
                    RealChanjetClient.mask(from));
            return TicketProtocol.REPLY_OK;
        }
        if (TicketProtocol.MSG_APP_TICKET.equals(type)) {
            String ticket = msg.path(TicketProtocol.FIELD_BIZ_CONTENT).path(TicketProtocol.FIELD_APP_TICKET).asText("");
            if (ticket.isBlank()) {
                log.warn("好生意推送 APP_TICKET 里没有 appTicket，不处理");
                return TicketProtocol.REPLY_OK;
            }
            store.saveTicket(appKey, ticket, clock.instant());
            log.info("好生意推送 收到 appTicket={}", RealChanjetClient.mask(ticket));
        } else {
            log.info("好生意推送 收到 {} 消息，只记日志", type.isBlank() ? "（无类型）" : type);
        }
        return TicketProtocol.REPLY_OK;
    }

    @Access(Who.ADMIN)
    @GetMapping("/api/chanjet/status")
    public ChanjetStatus chanjetStatus() {
        if (!(chanjet instanceof RealChanjetClient real)) {
            return new ChanjetStatus("fake", null, false, null, true);
        }
        RealChanjetClient.TokenMode mode = real.mode();
        if (mode != RealChanjetClient.TokenMode.TICKET) {
            return new ChanjetStatus(mode.name().toLowerCase(java.util.Locale.ROOT), null, false, null, true);
        }
        Instant now = clock.instant();
        AuthStore.State s = store.load(appKey);
        boolean fresh = s.appTicket() != null && s.ticketAt() != null
                && now.isBefore(s.ticketAt().plus(TicketProtocol.TICKET_TTL));
        boolean tokenOk = s.accessToken() != null && s.accessUntil() != null && now.isBefore(s.accessUntil());
        boolean refreshOk = s.refreshToken() != null && s.refreshUntil() != null && now.isBefore(s.refreshUntil());
        return new ChanjetStatus("ticket", s.ticketAt(), fresh, tokenOk ? s.accessUntil() : null, fresh || tokenOk || refreshOk);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}

package com.egg.chanjet;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 CHANJET_MODE 选实现（开发规格 3.5）：fake（默认）或 real。
 * real 模式下密钥一项都可以不填，服务照常启动；同步与回传时由 {@link RealChanjetClient} 报出缺哪几项。
 * openToken 的取法由 CHANJET_TOKEN_MODE 选（auto / certificate / static），见 {@link RealChanjetClient} 类注释。
 */
@Configuration
public class ChanjetConfig {

    /** 回传事务会跨过这次调用，超时 15 秒（开发规格 3.4）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Bean
    ChanjetClient chanjetClient(@Value("${egg.chanjet.mode:fake}") String mode,
            @Value("${egg.chanjet.base-url:https://openapi.chanjet.com}") String baseUrl,
            @Value("${egg.chanjet.app-key:}") String appKey, @Value("${egg.chanjet.app-secret:}") String appSecret,
            @Value("${egg.chanjet.book-id:}") String bookId, @Value("${egg.chanjet.open-token:}") String openToken,
            @Value("${egg.chanjet.certificate:}") String certificate,
            @Value("${egg.chanjet.token-mode:auto}") String tokenMode, ObjectMapper json) {
        return switch (mode) {
            case "fake" -> new FakeChanjetClient();
            case "real" -> new RealChanjetClient(
                    new RealChanjetClient.Settings(baseUrl, appKey, appSecret, bookId, openToken, certificate,
                            RealChanjetClient.TokenMode.parse(tokenMode), TIMEOUT), json);
            default -> throw new IllegalStateException("CHANJET_MODE=" + mode + " 不认识，只能是 fake 或 real");
        };
    }
}

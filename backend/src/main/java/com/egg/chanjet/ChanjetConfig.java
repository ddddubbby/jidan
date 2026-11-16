package com.egg.chanjet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按 CHANJET_MODE 选实现（开发规格 3.5）：fake（默认）或 real。 */
@Configuration
public class ChanjetConfig {

    @Bean
    ChanjetClient chanjetClient(@Value("${egg.chanjet.mode:fake}") String mode) {
        if ("fake".equals(mode)) {
            return new FakeChanjetClient();
        }
        // RealChanjetClient 在 P8-4；到那时这里改成按 real 返回它
        throw new IllegalStateException("CHANJET_MODE=" + mode + " 暂无对应实现，先用 fake");
    }
}

package com.egg.common;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /** servers 固定为相对路径，导出的 api/openapi.json 才不会带上某次启动的端口。 */
    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("鸡蛋采购入库系统 API").version("0.1.0"))
                .servers(List.of(new Server().url("/")));
    }
}

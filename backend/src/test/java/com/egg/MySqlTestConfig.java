package com.egg;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

/** 集成测试用的真 MySQL（Testcontainers，随机端口）。需要本机 Docker 在运行。 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestConfig {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>("mysql:8.4").withEnv("TZ", "Asia/Shanghai");
    }
}

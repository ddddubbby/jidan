package com.egg;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 空工程的冒烟：真库上启动，Flyway、MyBatis-Plus、健康检查、OpenAPI 都接通。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MySqlTestConfig.class)
class SkeletonTest {

    // 接口有变动时：mvn verify -Dopenapi.export=true 重新导出，并把 api/openapi.json 一并提交（开发规格 5.2）
    private static final Path SNAPSHOT = Path.of("..", "api", "openapi.json");

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SqlSessionFactory sqlSessionFactory;

    @Test
    void healthIsUpAndReachesDatabase() {
        var res = http.getForEntity("/api/health", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("UP");
        assertThat(res.getBody().get("db").asText()).isEqualTo("UP");
    }

    @Test
    void flywayHasRun() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void mybatisPlusIsWired() {
        assertThat(sqlSessionFactory.getConfiguration()).isInstanceOf(MybatisConfiguration.class);
    }

    @Test
    void unknownPathUsesUnifiedErrorFormat() {
        var res = http.getForEntity("/api/no-such-thing", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().get("message").asText()).isNotBlank();
    }

    @Test
    void openApiMatchesCommittedSnapshot() throws Exception {
        var mapper = new ObjectMapper();
        JsonNode actual = mapper.readTree(http.getForObject("/v3/api-docs", String.class));
        assertThat(actual.at("/paths/~1api~1health/get")).isNotEmpty();

        if (Boolean.getBoolean("openapi.export")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
        }
        assertThat(actual).as("api/openapi.json 过期，用 -Dopenapi.export=true 重新导出").isEqualTo(
                mapper.readTree(Files.readString(SNAPSHOT)));
    }
}

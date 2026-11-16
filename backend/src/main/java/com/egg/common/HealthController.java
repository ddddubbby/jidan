package com.egg.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    public record Health(String status, String db) {
    }

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 顺带探一次库：库连不上时返回 500，部署后一眼能看出是库的问题。 */
    @Access(Access.Who.PUBLIC)
    @GetMapping("/api/health")
    public Health health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return new Health("UP", "UP");
    }
}

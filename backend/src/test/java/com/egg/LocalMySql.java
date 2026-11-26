package com.egg;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 集成测试用的本机 MySQL 8.4（不用 Docker）。每次调用 {@link #createDatabase()} 在本机库上建一个
 * 新的空库，测试之间互不影响；用完由调用方 {@link #drop(String)}。
 *
 * <p>连接参数用环境变量覆盖，默认连本机 3306、账号 egg_test/egg_test（建法见 doc/本地启动指南-无Docker.md 第 2 节）：
 * EGG_TEST_DB_HOST、EGG_TEST_DB_PORT、EGG_TEST_DB_USER、EGG_TEST_DB_PASSWORD。账号要能建、删名字以 egg_test_ 开头的库。
 */
public final class LocalMySql {

    public static final String HOST = env("EGG_TEST_DB_HOST", "localhost");
    public static final String PORT = env("EGG_TEST_DB_PORT", "3306");
    public static final String USER = env("EGG_TEST_DB_USER", "egg_test");
    public static final String PASSWORD = env("EGG_TEST_DB_PASSWORD", "egg_test");

    private LocalMySql() {
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/?characterEncoding=utf8&connectionTimeZone=Asia/Shanghai";
    }

    /** 某个库的 JDBC 地址，参数与 application.yml 的默认地址一致。 */
    public static String jdbcUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
                + "?characterEncoding=utf8&connectionTimeZone=Asia/Shanghai";
    }

    /** 建一个新的空库（utf8mb4），返回库名。 */
    public static String createDatabase() {
        String name = "egg_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        exec("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        return name;
    }

    public static void drop(String database) {
        exec("DROP DATABASE IF EXISTS `" + database + "`");
    }

    private static void exec(String sql) {
        try (Connection c = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
                Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("连不上本机测试用 MySQL（" + HOST + ":" + PORT + "，账号 " + USER
                    + "）。先按 doc/本地启动指南-无Docker.md 装好并启动 MySQL 8.4，或用 EGG_TEST_DB_* 环境变量指定：" + e.getMessage(), e);
        }
    }
}

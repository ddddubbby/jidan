package com.egg;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 集成测试用的真 MySQL：连本机 MySQL 8.4（见 {@link LocalMySql}），每个测试上下文一个新的空库，
 * 上下文关闭时删掉。需要本机 MySQL 在运行，不需要 Docker。
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestConfig {

    @Bean(destroyMethod = "close")
    DataSource dataSource() {
        String db = LocalMySql.createDatabase();
        HikariDataSource ds = new HikariDataSource() {
            @Override
            public void close() {
                super.close();
                LocalMySql.drop(db);
            }
        };
        ds.setJdbcUrl(LocalMySql.jdbcUrl(db));
        ds.setUsername(LocalMySql.USER);
        ds.setPassword(LocalMySql.PASSWORD);
        return ds;
    }
}

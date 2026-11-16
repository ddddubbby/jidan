package com.egg;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EggApplication {

    public static void main(String[] args) {
        // “当日”以服务器日期为准，且统一东八区（开发规格 6.1）；必须在任何日期运算之前设定
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(EggApplication.class, args);
    }
}

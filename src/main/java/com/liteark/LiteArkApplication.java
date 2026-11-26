package com.liteark;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.liteark.mapper")
@EnableScheduling
public class LiteArkApplication {
    public static void main(String[] args) {
        SpringApplication.run(LiteArkApplication.class, args);
    }
}

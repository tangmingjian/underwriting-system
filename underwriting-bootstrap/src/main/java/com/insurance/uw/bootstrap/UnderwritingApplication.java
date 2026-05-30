package com.insurance.uw.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 核保配置化系统 - Spring Boot 启动入口
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.insurance.uw")
@MapperScan("com.insurance.uw.infrastructure.persistence")
public class UnderwritingApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnderwritingApplication.class, args);
    }

}

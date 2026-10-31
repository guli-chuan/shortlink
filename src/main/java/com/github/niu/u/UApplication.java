package com.github.niu.u;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author laoniu
 */
@SpringBootApplication
@MapperScan("com.github.niu.u.mapper")
@EnableScheduling
public class UApplication {

    public static void main(String[] args) {
        SpringApplication.run(UApplication.class, args);
    }

}

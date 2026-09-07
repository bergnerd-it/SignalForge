package com.financeally.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FinAllyApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinAllyApplication.class, args);
    }
}

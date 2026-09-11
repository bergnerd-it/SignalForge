package com.bergnerd.signalforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SignalForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignalForgeApplication.class, args);
    }
}

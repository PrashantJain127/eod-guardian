package com.caixa.eodguardian.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot runtime for EOD Guardian. Business logic lives in eod-core; this module is an adapter. */
@SpringBootApplication
@EnableScheduling
public class EodGuardianApplication {
    public static void main(String[] args) {
        SpringApplication.run(EodGuardianApplication.class, args);
    }
}

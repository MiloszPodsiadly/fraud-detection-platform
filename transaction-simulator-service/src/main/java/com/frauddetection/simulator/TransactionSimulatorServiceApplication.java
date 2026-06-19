package com.frauddetection.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class TransactionSimulatorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionSimulatorServiceApplication.class, args);
    }
}

package com.bankingplatform.transaction;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

// Scheduling is declared here rather than inherited. It currently arrives
// anyway, because common-kafka's outbox relay and processed-event retention
// each carry @EnableScheduling -- which means turning the outbox relay off
// would silently stop transfer reconciliation too. A job that never runs and
// never complains is the failure mode this platform has already hit twice.
@EnableScheduling
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class TransactionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}

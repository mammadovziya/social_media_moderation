package com.example.moderation.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    ModerationProperties.class,
    GatewayTransportProperties.class,
    AiWorkIdempotencySecurityProperties.class,
    BlockedTermsPolicyProperties.class,
    PolicyDistributionSecurityProperties.class
})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

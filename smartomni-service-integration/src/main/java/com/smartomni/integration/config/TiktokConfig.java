package com.smartomni.integration.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TiktokProperties.class)
public class TiktokConfig {

    /** Business time: state/token expiry. Separate from the signing clock (see AGENTS.md "Time"). */
    @Bean(name = "integrationBusinessClock")
    public Clock integrationBusinessClock() {
        return Clock.systemUTC();
    }

    /** Request-signature timestamp. Never adjusted by test business-time controls. */
    @Bean(name = "tiktokSigningClock")
    public Clock tiktokSigningClock() {
        return Clock.systemUTC();
    }
}

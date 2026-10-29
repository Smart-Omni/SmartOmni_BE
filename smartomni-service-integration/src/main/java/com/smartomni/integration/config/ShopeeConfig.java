package com.smartomni.integration.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShopeeProperties.class)
public class ShopeeConfig {

    /** Request-signature timestamp for Shopee. */
    @Bean(name = "shopeeSigningClock")
    public Clock shopeeSigningClock() {
        return Clock.systemUTC();
    }
}

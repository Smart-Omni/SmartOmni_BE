package com.smartomni.tenant.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sepay")
public class SepayProperties {

    private boolean enabled = true;
    private String accountNumber = "0000000000";
    private String bankId = "MBBank";
    private String accountHolder = "SMARTOMNI VIETNAM";
    private String webhookSecret = "test_sepay_secret_key_123";
    private BigDecimal proPlanPrice = new BigDecimal("499000.00");
    private String qrTemplate = "compact";
}

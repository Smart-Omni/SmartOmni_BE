package com.smartomni.migrations;

import com.smartomni.common.persistence.RlsDatabaseConfig;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Real Flyway/JPA/RLS stack with the Integration repositories and the TikTok connection store. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = {
        "com.smartomni.auth.entity", "com.smartomni.tenant.entity", "com.smartomni.catalog.entity",
        "com.smartomni.inventory.entity", "com.smartomni.order.entity",
        "com.smartomni.integration.entity", "com.smartomni.ai.entity"
})
@EnableJpaRepositories(basePackages = "com.smartomni.integration.repository")
@Import({RlsDatabaseConfig.class, TiktokConnectionStore.class})
public class TiktokTestApplication {
}

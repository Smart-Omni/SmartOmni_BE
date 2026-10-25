package com.smartomni.migrations;

import com.smartomni.common.persistence.RlsDatabaseConfig;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.config.TiktokConfig;
import com.smartomni.integration.service.catalog.JdbcCatalogMappingStore;
import com.smartomni.integration.service.catalog.PriceChangePolicy;
import com.smartomni.integration.service.catalog.TiktokPriceSyncService;
import com.smartomni.integration.service.catalog.TiktokProductImportService;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Real Flyway/JPA/RLS/JDBC stack with the catalog import and price sync; only the platform HTTP seam is faked. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = {
        "com.smartomni.auth.entity", "com.smartomni.tenant.entity", "com.smartomni.catalog.entity",
        "com.smartomni.inventory.entity", "com.smartomni.order.entity",
        "com.smartomni.integration.entity", "com.smartomni.ai.entity"
})
@EnableJpaRepositories(basePackages = "com.smartomni.integration.repository")
@Import({RlsDatabaseConfig.class, TiktokConfig.class, JdbcCatalogMappingStore.class, PriceChangePolicy.class,
        TiktokProductImportService.class, TiktokPriceSyncService.class, TiktokTenantConnections.class, TiktokCatalogClient.class})
public class TiktokCatalogTestApplication {

    static final FakeTiktokPlatform PLATFORM = new FakeTiktokPlatform();

    @Bean
    TiktokShopApiExecutor tiktokShopApiExecutor() {
        return PLATFORM;
    }
}

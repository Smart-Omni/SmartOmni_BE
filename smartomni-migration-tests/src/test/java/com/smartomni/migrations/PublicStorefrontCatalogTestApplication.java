package com.smartomni.migrations;

import com.smartomni.catalog.controller.PublicStorefrontController;
import com.smartomni.catalog.service.PublicStorefrontCatalogService;
import com.smartomni.common.persistence.RlsDatabaseConfig;
import com.smartomni.common.tenant.TenantPlanResolver;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;

/** Real Flyway schema, RLS transaction manager and the app role; the public storefront catalog end to end below HTTP. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = "com.smartomni.catalog.entity")
@Import({RlsDatabaseConfig.class, TenantPlanResolver.class, PublicStorefrontCatalogService.class, PublicStorefrontController.class})
public class PublicStorefrontCatalogTestApplication {
}

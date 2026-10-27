package com.smartomni.integration.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Shopee Open Platform application settings. The partner_id and partner_key belong to the SmartOmni
 * application (backend configuration), never to a shop owner. The same code talks to the Shopee simulator
 * or live Shopee: only URLs and credentials change.
 *
 * <p>Deliberately no {@code toString}: partner_key must never reach a log line.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "smartomni.shopee")
public class ShopeeProperties {

    /** Partner ID assigned by Shopee Open Platform. */
    private Long partnerId = 1001141L;

    /** Partner Key (secret for HMAC-SHA256 signature). */
    private String partnerKey = "";

    /** Base URL of the Shopee Open API gateway or Simulator. */
    private String baseUrl = "https://shopee-simulator.onrender.com";

    /** Seller consent page URL. */
    private String authorizationUrl = "https://shopee-simulator.onrender.com/api/v2/shop/auth_partner";

    /** Frontend callback page URL where the seller is redirected back. */
    private String redirectUri = "http://localhost:5174/merchant/channels/shopee/callback";

    private String tokenGetPath = "/api/v2/auth/token/get";
    private String tokenRefreshPath = "/api/v2/auth/access_token/get";
    private String shopInfoPath = "/api/v2/shop/get_shop_info";

    private Duration stateTtl = Duration.ofMinutes(10);
    private Duration accessTokenRefreshSkew = Duration.ofMinutes(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
}

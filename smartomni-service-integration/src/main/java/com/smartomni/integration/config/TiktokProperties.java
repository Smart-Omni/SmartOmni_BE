package com.smartomni.integration.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * TikTok Shop application settings. The app key/secret belong to the SmartOmni application (backend
 * configuration), never to a shop owner. The same code talks to the simulator or to TikTok: only the
 * URLs and credentials below change.
 *
 * <p>Every default is safe at startup; missing credentials fail at connect time, not at boot.
 * Deliberately no {@code toString}: the app secret must never reach a log line.
 *
 * <p>Items marked UNVERIFIED mirror {@code contracts/manifest.json} of the standalone simulator repo
 * (authorization URL, token endpoints and parameter names are not in any readable official source).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "smartomni.tiktok")
public class TiktokProperties {

    private String appKey = "";
    private String appSecret = "";

    /** Seller consent page (UNVERIFIED format). Empty means "not configured". */
    private String authorizationUrl = "";
    /** Query parameter names used on the authorization URL (UNVERIFIED). */
    private String authorizationAppKeyParam = "app_key";
    private String authorizationStateParam = "state";
    private String authorizationRedirectUriParam = "redirect_uri";
    /**
     * Where the seller is sent back after consent: the SmartOmni frontend callback page, which then
     * POSTs code/state to the backend with the Tenant Admin's JWT.
     */
    private String redirectUri = "";

    /** Token endpoints host (UNVERIFIED, secondary sources: https://auth.tiktok-shops.com). */
    private String tokenBaseUrl = "https://auth.tiktok-shops.com";
    private String tokenGetPath = "/api/v2/token/get";
    private String tokenRefreshPath = "/api/v2/token/refresh";
    private String authorizationCodeGrantType = "authorized_code";
    private String refreshGrantType = "refresh_token";

    /** Shop OpenAPI host (official OAS server). */
    private String openApiBaseUrl = "https://open-api.tiktokglobalshop.com";

    private Duration stateTtl = Duration.ofMinutes(10);
    private Duration accessTokenRefreshSkew = Duration.ofMinutes(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int maxPages = 200;

    /** page_size sent to Search Products (required by the OAS). */
    private int productPageSize = 50;

    /**
     * Optional template for the buyer-facing product page ({@code {productId}} is replaced); empty leaves
     * platform_product_url unset. Set it to the simulator's Buyer detail URL when running against the simulator.
     */
    private String productUrlTemplate = "";

    /** Scopes the connection needs. Official scope names are unresolved, so empty disables the check. */
    private List<String> requiredScopes = new ArrayList<>();

    /**
     * OpenAPI envelope codes that mean "access token rejected" (not documented in any readable source).
     * A listed code triggers one forced refresh and one retry, never more.
     */
    private List<Integer> tokenRejectedCodes = new ArrayList<>();

    /**
     * HTTP statuses that also mean "access token rejected" (default 401: unauthorized). Same rule for
     * the simulator and TikTok: one forced refresh and one retry at most.
     */
    private List<Integer> tokenRejectedHttpStatuses = new ArrayList<>(List.of(401));
}

package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.FeatureNotAvailableException;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.common.tenant.TenantPlanResolver;
import com.smartomni.integration.client.tiktok.FakeTransport;
import com.smartomni.integration.client.tiktok.TiktokAuthClient;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokConnectRequest;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.MarketplaceQuotaValidator;
import com.smartomni.integration.service.tiktok.ReconnectNotifier;
import com.smartomni.integration.service.tiktok.TiktokConnectionService;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore;
import com.smartomni.integration.service.tiktok.TokenCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TiktokConnectController Subscription Quota Tests")
class TiktokConnectControllerTest {

    private static final String KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private TiktokConnectionStore store;
    private TiktokConnectionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        store = mock(TiktokConnectionStore.class);
        TiktokProperties properties = new TiktokProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport(s -> FakeTransport.ok("{}"));
        TenantPlanResolver planResolver = new TenantPlanResolver((javax.sql.DataSource) null);
        service = new TiktokConnectionService(
                properties,
                store,
                new TiktokAuthClient(properties, transport, objectMapper),
                new TiktokOpenApiClient(properties, transport, clock, objectMapper),
                new TokenCipher(KEY),
                mock(ReconnectNotifier.class),
                clock,
                new MarketplaceQuotaValidator(planResolver));
        mockMvc = MockMvcBuilders.standaloneSetup(new TiktokConnectController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("POST /api/v1/tiktok/connect: Gói FREE đã có >= 1 sàn trả về 403 Forbidden")
    void testConnect_FreePlanExceedsLimit_Returns403() throws Exception {
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentUserId(10L);
        TenantContext.setCurrentRole("TENANT_ADMIN");
        TenantContext.setSubscriptionPlan("FREE");

        when(store.countActiveConnectionsExcluding(1L, MarketplaceConnection.Platform.TIKTOK_SHOP)).thenReturn(1L);

        TiktokConnectRequest request = new TiktokConnectRequest();
        request.setShopId("shop-tk-01");
        request.setShopName("Shop TikTok 1");

        mockMvc.perform(post("/api/v1/tiktok/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Gói Free chỉ hỗ trợ kết nối tối đa 1 sàn TMĐT."));
    }

    @Test
    @DisplayName("POST /api/v1/tiktok/connect: Gói FREE với 0 sàn được phép kết nối")
    void testConnect_FreePlanFirstShop_Success() throws Exception {
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentUserId(10L);
        TenantContext.setCurrentRole("TENANT_ADMIN");
        TenantContext.setSubscriptionPlan("FREE");

        when(store.countActiveConnectionsExcluding(1L, MarketplaceConnection.Platform.TIKTOK_SHOP)).thenReturn(0L);
        MarketplaceConnection saved = new MarketplaceConnection();
        saved.setId(100L);
        saved.setTenantId(1L);
        saved.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        saved.setShopId("shop-tk-01");
        saved.setShopName("Shop TikTok 1");
        saved.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        when(store.linkDirect(eq(1L), eq("shop-tk-01"), eq("Shop TikTok 1"), any())).thenReturn(saved);

        TiktokConnectRequest request = new TiktokConnectRequest();
        request.setShopId("shop-tk-01");
        request.setShopName("Shop TikTok 1");

        mockMvc.perform(post("/api/v1/tiktok/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shopId").value("shop-tk-01"))
                .andExpect(jsonPath("$.data.status").value("CONNECTED"));
    }

    @Test
    @DisplayName("POST /api/v1/tiktok/connect: Gói PRO đã có >= 1 sàn vẫn kết nối thành công")
    void testConnect_ProPlanMultipleShops_Success() throws Exception {
        TenantContext.setTenantId(2L);
        TenantContext.setCurrentUserId(20L);
        TenantContext.setCurrentRole("TENANT_ADMIN");
        TenantContext.setSubscriptionPlan("PRO");

        when(store.countActiveConnectionsExcluding(2L, MarketplaceConnection.Platform.TIKTOK_SHOP)).thenReturn(3L);
        MarketplaceConnection saved = new MarketplaceConnection();
        saved.setId(200L);
        saved.setTenantId(2L);
        saved.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        saved.setShopId("shop-tk-02");
        saved.setShopName("Pro TikTok Shop");
        saved.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        when(store.linkDirect(eq(2L), eq("shop-tk-02"), eq("Pro TikTok Shop"), any())).thenReturn(saved);

        TiktokConnectRequest request = new TiktokConnectRequest();
        request.setShopId("shop-tk-02");
        request.setShopName("Pro TikTok Shop");

        mockMvc.perform(post("/api/v1/tiktok/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shopId").value("shop-tk-02"));
    }
}


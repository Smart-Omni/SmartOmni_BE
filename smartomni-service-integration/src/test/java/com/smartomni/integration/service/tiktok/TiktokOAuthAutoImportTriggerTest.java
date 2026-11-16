package com.smartomni.integration.service.tiktok;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokAuthClient;
import com.smartomni.integration.client.tiktok.TiktokAuthClient.TokenGrant;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient.AuthorizedShop;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokCallbackRequest;
import com.smartomni.integration.dto.tiktok.TiktokConnectionDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.MarketplaceQuotaValidator;
import com.smartomni.integration.service.catalog.TiktokAutoImportAsyncLauncher;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.ConsumedState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("TiktokConnectionService OAuth -> WF-01 Auto-Import Trigger Test")
class TiktokOAuthAutoImportTriggerTest {

    @Test
    @DisplayName("OAuth callback completion triggers async WF-01 auto-import with tenantId and shopId")
    void oauthComplete_TriggersAsyncAutoImport() {
        TiktokProperties properties = new TiktokProperties();
        properties.setAppKey("test-key");
        properties.setAppSecret("test-secret");

        TiktokConnectionStore store = mock(TiktokConnectionStore.class);
        TiktokAuthClient authClient = mock(TiktokAuthClient.class);
        TiktokOpenApiClient openApi = mock(TiktokOpenApiClient.class);
        TokenCipher cipher = mock(TokenCipher.class);
        ReconnectNotifier notifier = mock(ReconnectNotifier.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
        TiktokAutoImportAsyncLauncher asyncLauncher = mock(TiktokAutoImportAsyncLauncher.class);

        TiktokConnectionService service = new TiktokConnectionService(
                properties, store, authClient, openApi, cipher, notifier, clock,
                new MarketplaceQuotaValidator((com.smartomni.common.tenant.TenantPlanResolver) null),
                asyncLauncher);

        when(store.consumeState(eq(10L), eq(99L), anyString(), any()))
                .thenReturn(new ConsumedState(TiktokConnectionStore.StateOutcome.OK, 55L));
        when(authClient.exchangeAuthorizationCode(eq("auth-code-1"), any()))
                .thenReturn(new TokenGrant("access-tok", "refresh-tok",
                        Instant.parse("2026-11-18T00:00:00Z"),
                        Instant.parse("2026-12-17T00:00:00Z"),
                        "open-id-1", List.of("product.info")));
        when(openApi.getAuthorizedShops("access-tok"))
                .thenReturn(List.of(new AuthorizedShop("SHOP-10", "CIPHER-10", "CODE", "My TikTok Shop", "VN", "LOCAL")));
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        MarketplaceConnection saved = new MarketplaceConnection();
        saved.setId(55L);
        saved.setTenantId(10L);
        saved.setShopId("SHOP-10");
        saved.setShopName("My TikTok Shop");
        saved.setStatus(ConnectionStatus.CONNECTED);
        saved.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        when(store.link(eq(10L), eq(55L), any())).thenReturn(saved);

        TiktokCallbackRequest req = new TiktokCallbackRequest();
        req.setCode("auth-code-1");
        req.setState("state-1");

        try (var scope = TenantContext.openScope(10L, "TENANT_ADMIN", 99L)) {
            TiktokConnectionDto dto = service.complete(req);
            assertEquals("SHOP-10", dto.shopId());
        }

        verify(asyncLauncher, times(1)).launchAfterOAuth(10L, 55L, "SHOP-10", "TENANT_ADMIN");
    }
}

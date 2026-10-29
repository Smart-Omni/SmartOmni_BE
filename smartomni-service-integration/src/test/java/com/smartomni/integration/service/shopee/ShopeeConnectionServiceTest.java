package com.smartomni.integration.service.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.shopee.FakeShopeeTransport;
import com.smartomni.integration.client.shopee.ShopeeAuthClient;
import com.smartomni.integration.client.shopee.ShopeeOpenApiClient;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.dto.shopee.ShopeeAuthorizationStartDto;
import com.smartomni.integration.dto.shopee.ShopeeCallbackRequest;
import com.smartomni.integration.dto.shopee.ShopeeConnectionDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.shopee.ShopeeConnectionStore.ConsumedState;
import com.smartomni.integration.service.shopee.ShopeeConnectionStore.StateOutcome;
import com.smartomni.integration.service.tiktok.TokenCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ShopeeConnectionServiceTest {

    private static final String KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final String TOKEN_JSON = "{\"access_token\":\"AT-shopee-secret\",\"refresh_token\":\"RT-shopee-secret\",\"expire_in\":14400,\"shop_id_list\":[727720655]}";
    private static final String SHOP_JSON = "{\"shop_name\":\"SmartOmni Official Flagship\",\"region\":\"VN\",\"status\":\"NORMAL\",\"is_vacation\":false}";

    private ShopeeProperties properties;
    private ShopeeConnectionStore store;
    private TokenCipher cipher;
    private FakeShopeeTransport transport;
    private ShopeeConnectionService service;

    @BeforeEach
    void setUp() {
        properties = new ShopeeProperties();
        properties.setPartnerId(1001141L);
        properties.setPartnerKey("shopee_mock_secret_key_2026");
        properties.setAuthorizationUrl("https://shopee-simulator.onrender.com/api/v2/shop/auth_partner");
        properties.setRedirectUri("http://localhost:5174/merchant/channels/shopee/callback");
        store = mock(ShopeeConnectionStore.class);
        cipher = new TokenCipher(KEY);
        setTransport(new FakeShopeeTransport(s -> s.uri().getPath().startsWith("/api/v2/auth")
                ? FakeShopeeTransport.ok(TOKEN_JSON)
                : FakeShopeeTransport.ok(SHOP_JSON)));
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentUserId(10L);
        TenantContext.setCurrentRole("ADMIN");
    }

    private void setTransport(FakeShopeeTransport t) {
        transport = t;
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ShopeeConnectionService(properties, store,
                new ShopeeAuthClient(properties, transport, mapper),
                new ShopeeOpenApiClient(properties, transport, clock, mapper),
                cipher, clock);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ShopeeCallbackRequest callback(String code, String shopId, String state, String error) {
        ShopeeCallbackRequest r = new ShopeeCallbackRequest();
        r.setCode(code);
        r.setShopId(shopId);
        r.setState(state);
        r.setError(error);
        return r;
    }

    private MarketplaceConnection savedConnection(ConnectionStatus status) {
        MarketplaceConnection c = new MarketplaceConnection();
        c.setId(88L);
        c.setTenantId(1L);
        c.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        c.setStatus(status);
        c.setShopId("727720655");
        c.setShopName("SmartOmni Official Flagship");
        c.setShopRegion("VN");
        c.setConnectedAt(NOW);
        c.setAccessTokenExpiresAt(NOW.plusSeconds(14400));
        c.setRefreshTokenExpiresAt(NOW.plusSeconds(30L * 24 * 3600));
        return c;
    }

    @Test
    void startReturnsExpiringStateBoundToTenantAndUser() {
        when(store.prepareStart(eq(1L), eq(10L), any(), any())).thenReturn(88L);

        ShopeeAuthorizationStartDto start = service.start();

        assertNotNull(start.authorizationUrl());
        assertTrue(start.authorizationUrl().startsWith("https://shopee-simulator.onrender.com/api/v2/shop/auth_partner?"));
        assertTrue(start.authorizationUrl().contains("partner_id=1001141"));
        assertTrue(start.authorizationUrl().contains("redirect_uri="));
        assertTrue(start.authorizationUrl().contains("state="));
        assertEquals(NOW.plus(properties.getStateTtl()), start.expiresAt());

        verify(store).prepareStart(eq(1L), eq(10L), any(), eq(NOW.plus(properties.getStateTtl())));
    }

    @Test
    void startRejectsNonAdmin() {
        TenantContext.setCurrentRole("STAFF");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.start());
        assertEquals("TENANT_ADMIN_REQUIRED", ex.getErrorCode());
    }

    @Test
    void startFailsIfCredentialsMissing() {
        properties.setPartnerKey("");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.start());
        assertEquals("SHOPEE_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    void completeConsumesStateFirstAndRejectsInvalidOrExpired() {
        when(store.consumeState(eq(1L), eq(10L), any(), eq(NOW)))
                .thenReturn(new ConsumedState(StateOutcome.INVALID, null));

        BusinessException inv = assertThrows(BusinessException.class, () ->
                service.complete(callback("CODE", "727720655", "STATE", null)));
        assertEquals("SHOPEE_STATE_INVALID", inv.getErrorCode());

        when(store.consumeState(eq(1L), eq(10L), any(), eq(NOW)))
                .thenReturn(new ConsumedState(StateOutcome.EXPIRED, null));
        BusinessException exp = assertThrows(BusinessException.class, () ->
                service.complete(callback("CODE", "727720655", "STATE", null)));
        assertEquals("SHOPEE_STATE_EXPIRED", exp.getErrorCode());

        when(store.consumeState(eq(1L), eq(10L), any(), eq(NOW)))
                .thenReturn(new ConsumedState(StateOutcome.REUSED, null));
        BusinessException reused = assertThrows(BusinessException.class, () ->
                service.complete(callback("CODE", "727720655", "STATE", null)));
        assertEquals("SHOPEE_STATE_REUSED", reused.getErrorCode());
    }

    @Test
    void completeRejectsDeniedAuthorization() {
        when(store.consumeState(eq(1L), eq(10L), any(), eq(NOW)))
                .thenReturn(new ConsumedState(StateOutcome.OK, 88L));

        BusinessException denied = assertThrows(BusinessException.class, () ->
                service.complete(callback(null, null, "STATE", "access_denied")));
        assertEquals("SHOPEE_AUTHORIZATION_DENIED", denied.getErrorCode());
    }

    @Test
    void completeRejectsMissingCode() {
        when(store.consumeState(eq(1L), eq(10L), any(), eq(NOW)))
                .thenReturn(new ConsumedState(StateOutcome.OK, 88L));

        BusinessException missing = assertThrows(BusinessException.class, () ->
                service.complete(callback("", "727720655", "STATE", null)));
        assertEquals("SHOPEE_CODE_MISSING", missing.getErrorCode());
    }

    @Test
    void completeExchangesCodeFetchesShopInfoAndEncryptsTokens() {
        when(store.consumeState(eq(1L), eq(10L), any(), eq(NOW)))
                .thenReturn(new ConsumedState(StateOutcome.OK, 88L));
        when(store.link(eq(1L), eq(88L), any())).thenReturn(savedConnection(ConnectionStatus.CONNECTED));

        ShopeeConnectionDto dto = service.complete(callback("CODE-1", "727720655", "STATE-1", null));

        assertEquals("CONNECTED", dto.status());
        assertEquals("727720655", dto.shopId());
        assertEquals("SmartOmni Official Flagship", dto.shopName());
        assertEquals("VN", dto.shopRegion());

        ArgumentCaptor<ShopeeConnectionStore.LinkRequest> captor = ArgumentCaptor.forClass(ShopeeConnectionStore.LinkRequest.class);
        verify(store).link(eq(1L), eq(88L), captor.capture());
        ShopeeConnectionStore.LinkRequest link = captor.getValue();
        assertEquals("727720655", link.shopId());
        assertEquals("SmartOmni Official Flagship", link.shopName());
        assertNotNull(link.accessEncrypted());
        assertNotNull(link.refreshEncrypted());
        assertEquals("AT-shopee-secret", cipher.decrypt(link.accessEncrypted()));
        assertEquals("RT-shopee-secret", cipher.decrypt(link.refreshEncrypted()));
    }

    @Test
    void completeHandlesConflictIfShopAlreadyLinked() {
        when(store.consumeState(eq(1L), eq(10L), any(), eq(NOW)))
                .thenReturn(new ConsumedState(StateOutcome.OK, 88L));
        when(store.link(any(), any(), any())).thenThrow(new DataIntegrityViolationException("unique index violation"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.complete(callback("CODE-1", "727720655", "STATE-1", null)));
        assertEquals("SHOPEE_SHOP_ALREADY_LINKED", ex.getErrorCode());
    }

    @Test
    void currentReturnsDtoWithoutSecrets() {
        when(store.findCurrent(1L)).thenReturn(Optional.of(savedConnection(ConnectionStatus.CONNECTED)));
        Optional<ShopeeConnectionDto> dto = service.current();
        assertTrue(dto.isPresent());
        assertEquals("727720655", dto.get().shopId());
        assertEquals("SmartOmni Official Flagship", dto.get().shopName());
    }

    @Test
    void disconnectClearsTokens() {
        when(store.findCurrent(1L)).thenReturn(Optional.of(savedConnection(ConnectionStatus.CONNECTED)));
        when(store.terminate(eq(1L), eq(88L), eq(ConnectionStatus.DISCONNECTED), any(), eq(NOW)))
                .thenReturn(Optional.of(savedConnection(ConnectionStatus.DISCONNECTED)));

        ShopeeConnectionDto dto = service.disconnect();
        assertEquals("DISCONNECTED", dto.status());
        verify(store).terminate(eq(1L), eq(88L), eq(ConnectionStatus.DISCONNECTED), any(), eq(NOW));
    }
}

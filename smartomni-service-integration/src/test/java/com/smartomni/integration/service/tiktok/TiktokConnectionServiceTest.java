package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.FakeTransport;
import com.smartomni.integration.client.tiktok.TiktokAuthClient;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokAuthorizationStartDto;
import com.smartomni.integration.dto.tiktok.TiktokCallbackRequest;
import com.smartomni.integration.dto.tiktok.TiktokConnectionDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.ConsumedState;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.StateOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TiktokConnectionServiceTest {

    private static final String KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final String TOKEN_JSON = "{\"access_token\":\"AT-secret\",\"access_token_expire_in\":1790000000,"
            + "\"refresh_token\":\"RT-secret\",\"refresh_token_expire_in\":1800000000,\"open_id\":\"OID-1\"%s}";
    private static final String ONE_SHOP = "{\"shops\":[{\"cipher\":\"CIPHER-1\",\"code\":\"K\",\"id\":\"7000000000000000001\","
            + "\"name\":\"Shop One\",\"region\":\"VN\",\"seller_type\":\"LOCAL\"}]}";

    private TiktokProperties properties;
    private TiktokConnectionStore store;
    private ReconnectNotifier notifier;
    private TokenCipher cipher;
    private FakeTransport transport;
    private TiktokConnectionService service;

    @BeforeEach
    void setUp() {
        properties = new TiktokProperties();
        properties.setAppKey("app-key");
        properties.setAppSecret("app-secret");
        properties.setAuthorizationUrl("http://simulator.test:3090/consent");
        properties.setRedirectUri("http://localhost:3000/integrations/tiktok/callback");
        store = mock(TiktokConnectionStore.class);
        notifier = mock(ReconnectNotifier.class);
        cipher = new TokenCipher(KEY);
        setTransport(new FakeTransport(s -> s.uri().getPath().startsWith("/api/v2/token")
                ? FakeTransport.ok(String.format(TOKEN_JSON, ""))
                : FakeTransport.ok(ONE_SHOP)));
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentUserId(5L);
        TenantContext.setCurrentRole("ADMIN");
    }

    private void setTransport(FakeTransport t) {
        transport = t;
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new TiktokConnectionService(properties, store, new TiktokAuthClient(properties, transport, mapper),
                new TiktokOpenApiClient(properties, transport, clock, mapper), cipher, notifier, clock);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static TiktokCallbackRequest callback(String code, String state, String error) {
        TiktokCallbackRequest r = new TiktokCallbackRequest();
        r.setCode(code);
        r.setState(state);
        r.setError(error);
        return r;
    }

    private MarketplaceConnection savedConnection(ConnectionStatus status) {
        MarketplaceConnection c = new MarketplaceConnection();
        c.setId(77L);
        c.setTenantId(1L);
        c.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        c.setStatus(status);
        c.setShopId("7000000000000000001");
        c.setShopName("Shop One");
        c.setShopCipher("CIPHER-1");
        c.setAccessTokenEncrypted("ciphertext-a");
        c.setRefreshTokenEncrypted("ciphertext-r");
        return c;
    }

    private void stateIs(StateOutcome outcome) {
        when(store.consumeState(eq(1L), eq(5L), anyString(), any())).thenReturn(
                new ConsumedState(outcome, outcome == StateOutcome.OK ? 77L : null));
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    // ---- start

    @Test
    void startStoresOnlyTheHashAndReturnsAUrlWithTheRawState() {
        TiktokAuthorizationStartDto dto = service.start();

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store).prepareStart(eq(1L), eq(5L), hash.capture(), eq(NOW.plus(properties.getStateTtl())));
        URI url = URI.create(dto.authorizationUrl());
        String query = URLDecoder.decode(url.getRawQuery(), StandardCharsets.UTF_8);
        String rawState = query.replaceAll(".*state=([^&]+).*", "$1");
        assertEquals("simulator.test", url.getHost());
        assertTrue(query.contains("app_key=app-key"));
        assertTrue(query.contains("redirect_uri=http://localhost:3000/integrations/tiktok/callback"));
        assertEquals(64, hash.getValue().length());
        assertNotEquals(rawState, hash.getValue(), "the raw state is never stored");
        assertEquals(TiktokConnectionService.hash(rawState), hash.getValue());
        assertFalse(dto.authorizationUrl().contains("app-secret"));
        assertEquals(NOW.plus(properties.getStateTtl()), dto.expiresAt());
    }

    @Test
    void everyStartProducesADifferentState() {
        String a = service.start().authorizationUrl();
        String b = service.start().authorizationUrl();
        assertNotEquals(a, b);
    }

    @Test
    void startRetriesOnceWhenTwoRequestsCreateTheConnectionRowTogether() {
        when(store.prepareStart(anyLong(), anyLong(), anyString(), any()))
                .thenThrow(new DataIntegrityViolationException("dup")).thenReturn(77L);
        assertNotNull(service.start());
        verify(store, times(2)).prepareStart(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void tenantAdminRoleCanStartConnection() {
        TenantContext.setCurrentRole("TENANT_ADMIN");
        assertNotNull(service.start());
        verify(store).prepareStart(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void onlyATenantAdminMayStartCompleteOrDisconnect() {
        for (String role : new String[]{"MANAGER", "SUPER_ADMIN", null}) {
            TenantContext.setCurrentRole(role);
            assertEquals("TENANT_ADMIN_REQUIRED", assertThrows(BusinessException.class, () -> service.start()).getErrorCode());
            assertEquals("TENANT_ADMIN_REQUIRED", assertThrows(BusinessException.class,
                    () -> service.complete(callback("c", "s", null))).getErrorCode());
            assertEquals("TENANT_ADMIN_REQUIRED", assertThrows(BusinessException.class, () -> service.disconnect()).getErrorCode());
        }
        TenantContext.clear();
        assertEquals("TENANT_REQUIRED", assertThrows(BusinessException.class, () -> service.start()).getErrorCode());
        verifyNoInteractions(store);
    }

    @Test
    void missingCredentialsOrAuthorizationUrlFailAtStartNotAtBoot() {
        properties.setAuthorizationUrl("");
        assertEquals("TIKTOK_NOT_CONFIGURED", assertThrows(BusinessException.class, () -> service.start()).getErrorCode());
        properties.setAuthorizationUrl("http://x");
        properties.setAppSecret("");
        assertEquals("TIKTOK_NOT_CONFIGURED", assertThrows(BusinessException.class, () -> service.start()).getErrorCode());
        verifyNoInteractions(store);
    }

    // ---- callback

    @Test
    void invalidExpiredAndReusedStatesStopBeforeAnyTokenCall() {
        record Case(StateOutcome outcome, String code) { }
        for (Case c : List.of(new Case(StateOutcome.INVALID, "TIKTOK_STATE_INVALID"),
                new Case(StateOutcome.EXPIRED, "TIKTOK_STATE_EXPIRED"), new Case(StateOutcome.REUSED, "TIKTOK_STATE_REUSED"))) {
            stateIs(c.outcome());
            BusinessException e = assertThrows(BusinessException.class, () -> service.complete(callback("code", "state", null)));
            assertEquals(c.code(), e.getErrorCode());
        }
        assertThrows(BusinessException.class, () -> service.complete(callback("code", " ", null)));
        assertTrue(transport.sent.isEmpty(), "no HTTP call for a bad state");
        verify(store, never()).link(anyLong(), anyLong(), any());
    }

    @Test
    void theTenantComesFromTheJwtContextNotFromTheRequest() {
        stateIs(StateOutcome.INVALID); // state bound to another tenant is invisible to this tenant
        TenantContext.setTenantId(2L);
        when(store.consumeState(eq(2L), eq(5L), anyString(), any())).thenReturn(new ConsumedState(StateOutcome.INVALID, null));
        assertThrows(BusinessException.class, () -> service.complete(callback("code", "state-of-tenant-1", null)));
        verify(store).consumeState(eq(2L), eq(5L), eq(TiktokConnectionService.hash("state-of-tenant-1")), any());
        verify(store, never()).link(anyLong(), anyLong(), any());
        assertTrue(transport.sent.isEmpty());
        assertFalse(java.util.Arrays.stream(TiktokCallbackRequest.class.getDeclaredFields())
                .anyMatch(f -> f.getName().toLowerCase().contains("tenant")), "the callback request has no tenant field");
    }

    @Test
    void sellerDenyingConsentConsumesTheStateAndStoresNothing() {
        stateIs(StateOutcome.OK);
        BusinessException e = assertThrows(BusinessException.class, () -> service.complete(callback(null, "state", "access_denied")));
        assertEquals("TIKTOK_AUTHORIZATION_DENIED", e.getErrorCode());
        assertTrue(transport.sent.isEmpty());
        verify(store, never()).link(anyLong(), anyLong(), any());
    }

    @Test
    void successfulCallbackExchangesCodeChecksShopAndStoresEncryptedTokens() {
        stateIs(StateOutcome.OK);
        when(store.link(eq(1L), eq(77L), any())).thenReturn(savedConnection(ConnectionStatus.CONNECTED));

        TiktokConnectionDto dto = service.complete(callback("the-code", "state", null));

        assertEquals("CONNECTED", dto.status());
        assertFalse(dto.needsReconnect());
        assertEquals(2, transport.sent.size());
        assertEquals("/api/v2/token/get", transport.sent.get(0).uri().getPath());
        assertEquals("/authorization/202309/shops", transport.sent.get(1).uri().getPath());
        assertEquals("AT-secret", transport.sent.get(1).headers().get("x-tts-access-token"));

        ArgumentCaptor<TiktokConnectionStore.LinkRequest> link = ArgumentCaptor.forClass(TiktokConnectionStore.LinkRequest.class);
        verify(store).link(eq(1L), eq(77L), link.capture());
        TiktokConnectionStore.LinkRequest saved = link.getValue();
        assertEquals("7000000000000000001", saved.shopId());
        assertEquals("CIPHER-1", saved.shopCipher());
        assertEquals("OID-1", saved.sellerOpenId());
        assertEquals(ConnectionStatus.CONNECTED, saved.status());
        assertFalse(saved.accessEncrypted().contains("AT-secret"));
        assertEquals("AT-secret", cipher.decrypt(saved.accessEncrypted()));
        assertEquals("RT-secret", cipher.decrypt(saved.refreshEncrypted()));
        verifyNoInteractions(notifier);
    }

    @Test
    void repeatedCallbackIsRejectedAndDoesNotRelinkOrCallTheTokenEndpointAgain() {
        stateIs(StateOutcome.OK);
        when(store.link(eq(1L), eq(77L), any())).thenReturn(savedConnection(ConnectionStatus.CONNECTED));
        service.complete(callback("the-code", "state", null));
        int httpCalls = transport.sent.size();

        stateIs(StateOutcome.REUSED); // second delivery of the same redirect
        assertEquals("TIKTOK_STATE_REUSED", assertThrows(BusinessException.class,
                () -> service.complete(callback("the-code", "state", null))).getErrorCode());
        assertEquals(httpCalls, transport.sent.size());
        verify(store, times(1)).link(anyLong(), anyLong(), any());
    }

    @Test
    void grantedScopesMissingTheRequiredOnesGiveMissingScopeAndAnAlert() {
        properties.setRequiredScopes(List.of("orders.read", "products.read"));
        setTransport(new FakeTransport(s -> s.uri().getPath().startsWith("/api/v2/token")
                ? FakeTransport.ok(String.format(TOKEN_JSON, ",\"granted_scopes\":[\"orders.read\"]"))
                : FakeTransport.ok(ONE_SHOP)));
        stateIs(StateOutcome.OK);
        when(store.link(eq(1L), eq(77L), any())).thenReturn(savedConnection(ConnectionStatus.MISSING_SCOPE));

        TiktokConnectionDto dto = service.complete(callback("c", "s", null));

        ArgumentCaptor<TiktokConnectionStore.LinkRequest> link = ArgumentCaptor.forClass(TiktokConnectionStore.LinkRequest.class);
        verify(store).link(eq(1L), eq(77L), link.capture());
        assertEquals(ConnectionStatus.MISSING_SCOPE, link.getValue().status());
        assertTrue(dto.needsReconnect());
        verify(notifier).reconnectRequired(1L, 77L, "SCOPE_MISSING");
    }

    @Test
    void whenScopesAreRequiredButTheResponseDoesNotListAnyItFailsClosed() {
        properties.setRequiredScopes(List.of("orders.read"));
        stateIs(StateOutcome.OK);
        when(store.link(eq(1L), eq(77L), any())).thenReturn(savedConnection(ConnectionStatus.MISSING_SCOPE));
        service.complete(callback("c", "s", null));
        ArgumentCaptor<TiktokConnectionStore.LinkRequest> link = ArgumentCaptor.forClass(TiktokConnectionStore.LinkRequest.class);
        verify(store).link(eq(1L), eq(77L), link.capture());
        assertEquals(ConnectionStatus.MISSING_SCOPE, link.getValue().status());
    }

    @Test
    void zeroOrSeveralAuthorizedShopsAreRefusedBecauseOneShopPerTenantIsAllowed() {
        stateIs(StateOutcome.OK);
        setTransport(new FakeTransport(s -> s.uri().getPath().startsWith("/api/v2/token")
                ? FakeTransport.ok(String.format(TOKEN_JSON, "")) : FakeTransport.ok("{\"shops\":[]}")));
        assertEquals("TIKTOK_NO_SHOP", assertThrows(BusinessException.class, () -> service.complete(callback("c", "s", null))).getErrorCode());

        setTransport(new FakeTransport(s -> s.uri().getPath().startsWith("/api/v2/token")
                ? FakeTransport.ok(String.format(TOKEN_JSON, ""))
                : FakeTransport.ok("{\"shops\":[{\"cipher\":\"A\",\"id\":\"1\"},{\"cipher\":\"B\",\"id\":\"2\"}]}")));
        assertEquals("TIKTOK_MULTIPLE_SHOPS", assertThrows(BusinessException.class, () -> service.complete(callback("c", "s", null))).getErrorCode());
        verify(store, never()).link(anyLong(), anyLong(), any());
    }

    @Test
    void shopLinkedToAnotherTenantIsRefused() {
        stateIs(StateOutcome.OK);
        when(store.link(eq(1L), eq(77L), any())).thenThrow(new DataIntegrityViolationException("uq_marketplace_connections_tiktok_shop"));
        BusinessException e = assertThrows(BusinessException.class, () -> service.complete(callback("c", "s", null)));
        assertEquals("TIKTOK_SHOP_ALREADY_LINKED", e.getErrorCode());
        assertFalse(e.getMessage().contains("uq_"), "database details are not echoed");
    }

    @Test
    void rejectedOrUnavailableTokenExchangeIsMappedWithoutLeakingTheUpstreamUrl() {
        stateIs(StateOutcome.OK);
        setTransport(new FakeTransport(s -> FakeTransport.apiError(10000, "invalid auth_code")));
        BusinessException rejected = assertThrows(BusinessException.class, () -> service.complete(callback("bad-code", "s", null)));
        assertEquals("TIKTOK_CODE_REJECTED", rejected.getErrorCode());
        assertFalse(rejected.getMessage().contains("bad-code") || rejected.getMessage().contains("app-secret"));

        setTransport(new FakeTransport(s -> new com.smartomni.integration.client.tiktok.TiktokHttpTransport.Response(503, "")));
        assertEquals("TIKTOK_UNAVAILABLE", assertThrows(BusinessException.class,
                () -> service.complete(callback("c", "s", null))).getErrorCode());
        verify(store, never()).link(anyLong(), anyLong(), any());
    }

    // ---- revoke / disconnect / dto

    @Test
    void revokingAlertsOnlyOnTheFirstTransition() {
        when(store.terminate(eq(1L), eq(77L), eq(ConnectionStatus.REVOKED), eq("REVOKED"), any()))
                .thenReturn(java.util.Optional.of(savedConnection(ConnectionStatus.REVOKED))).thenReturn(java.util.Optional.empty());
        service.markRevoked(77L);
        service.markRevoked(77L);
        verify(notifier, times(1)).reconnectRequired(1L, 77L, "REVOKED");
    }

    @Test
    void dtoNeverCarriesTokensSecretsOrTheCipher() throws Exception {
        MarketplaceConnection c = savedConnection(ConnectionStatus.REFRESH_FAILED);
        c.setSellerOpenId("OID-1");
        String json = new ObjectMapper().findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .writeValueAsString(TiktokConnectionDto.from(c));
        for (String leaked : List.of("ciphertext-a", "ciphertext-r", "CIPHER-1", "OID-1", "secret")) {
            assertFalse(json.toLowerCase().contains(leaked.toLowerCase()), "leaked " + leaked + " in " + json);
        }
        assertTrue(json.contains("\"needsReconnect\":true"));
        for (var component : TiktokConnectionDto.class.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            assertFalse(name.contains("secret") || name.contains("cipher") || (name.contains("token") && !name.endsWith("expiresat")),
                    "suspicious DTO field " + name);
        }
    }
}

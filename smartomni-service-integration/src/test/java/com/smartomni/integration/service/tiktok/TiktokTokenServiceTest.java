package com.smartomni.integration.service.tiktok;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.FakeTransport;
import com.smartomni.integration.client.tiktok.TiktokAuthClient;
import com.smartomni.integration.client.tiktok.TiktokHttpTransport;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.TokenSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TiktokTokenServiceTest {

    private static final String KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    private final TokenCipher cipher = new TokenCipher(KEY);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<String> notifications = new ArrayList<>();
    private final ReconnectNotifier notifier = (tenant, connection, reason) -> {
        synchronized (notifications) {
            notifications.add(tenant + ":" + connection + ":" + reason);
        }
    };
    private TiktokProperties properties;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        properties = new TiktokProperties();
        properties.setAppKey("app-key");
        properties.setAppSecret("super-app-secret");
        TenantContext.setTenantId(1L);
        logs = new ListAppender<>();
        logs.start();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        TenantContext.clear();
    }

    private InMemoryTokenStore store(Instant accessExpires, Instant refreshExpires) {
        return new InMemoryTokenStore(new TokenSnapshot(10L, 1L, ConnectionStatus.CONNECTED, "CIPHER-A", 1,
                cipher.encrypt("old-access"), cipher.encrypt("old-refresh"), accessExpires, refreshExpires));
    }

    private TiktokTokenService service(InMemoryTokenStore store, FakeTransport transport) {
        return new TiktokTokenService(store, new TiktokAuthClient(properties, transport, new ObjectMapper()),
                cipher, notifier, properties, clock);
    }

    private static TiktokHttpTransport.Response refreshOk(String access, String refresh) {
        return FakeTransport.ok("{\"access_token\":\"" + access + "\",\"access_token_expire_in\":" + NOW.plusSeconds(86400).getEpochSecond()
                + ",\"refresh_token\":\"" + refresh + "\",\"refresh_token_expire_in\":" + NOW.plusSeconds(86400L * 30).getEpochSecond() + "}");
    }

    @Test
    void freshTokenIsReturnedWithoutCallingThePlatform() {
        FakeTransport transport = new FakeTransport(s -> fail("no refresh expected"));
        TiktokTokenService.Credentials c = service(store(NOW.plusSeconds(3600), NOW.plusSeconds(86400)), transport).credentials(10L);
        assertEquals("old-access", c.accessToken());
        assertEquals("CIPHER-A", c.shopCipher());
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void expiredAccessTokenIsRefreshedAndBothTokensAreStoredEncrypted() {
        InMemoryTokenStore store = store(NOW.minusSeconds(10), NOW.plusSeconds(86400));
        FakeTransport transport = new FakeTransport(s -> refreshOk("new-access", "new-refresh"));
        TiktokTokenService.Credentials c = service(store, transport).credentials(10L);

        assertEquals("new-access", c.accessToken());
        assertEquals(1, transport.sent.size());
        assertTrue(transport.sent.get(0).uri().getRawQuery().contains("refresh_token=old-refresh"));
        assertEquals(2, store.snapshot().version());
        assertFalse(store.snapshot().accessEncrypted().contains("new-access"));
        assertEquals("new-refresh", cipher.decrypt(store.snapshot().refreshEncrypted()));
        assertTrue(notifications.isEmpty());
    }

    @Test
    void tokenInsideTheRefreshSkewIsRefreshedEarly() {
        InMemoryTokenStore store = store(NOW.plusSeconds(60), NOW.plusSeconds(86400)); // skew is 5 minutes
        FakeTransport transport = new FakeTransport(s -> refreshOk("new-access", "new-refresh"));
        assertEquals("new-access", service(store, transport).credentials(10L).accessToken());
    }

    @Test
    void concurrentRequestsTriggerExactlyOneRefreshAndAllGetTheSameToken() throws Exception {
        InMemoryTokenStore store = store(NOW.minusSeconds(10), NOW.plusSeconds(86400));
        AtomicInteger refreshCalls = new AtomicInteger();
        FakeTransport transport = new FakeTransport(s -> {
            refreshCalls.incrementAndGet();
            try {
                Thread.sleep(150); // widen the race window
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return refreshOk("new-access", "new-refresh");
        });
        TiktokTokenService service = service(store, transport);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                TenantContext.setTenantId(1L);
                start.await();
                return service.credentials(10L).accessToken();
            }));
        }
        start.countDown();
        for (Future<String> r : results) {
            assertEquals("new-access", r.get());
        }
        pool.shutdown();
        assertEquals(1, refreshCalls.get(), "single refresh for 16 concurrent requests");
        assertEquals(1, store.rotations);
    }

    @Test
    void twoInstancesRacingOnTheDatabaseLeaveOneWinnerAndBothUseItsTokens() throws Exception {
        InMemoryTokenStore store = store(NOW.minusSeconds(10), NOW.plusSeconds(86400));
        AtomicInteger n = new AtomicInteger();
        CountDownLatch bothInFlight = new CountDownLatch(2);
        FakeTransport transport = new FakeTransport(s -> {
            int mine = n.incrementAndGet();
            bothInFlight.countDown();
            try {
                bothInFlight.await(); // both instances hold a refresh response before either writes
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return refreshOk("access-" + mine, "refresh-" + mine);
        });
        TiktokTokenService instanceA = service(store, transport);
        TiktokTokenService instanceB = service(store, transport); // separate JVM locks = separate instances
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> a = pool.submit(() -> { TenantContext.setTenantId(1L); return instanceA.credentials(10L).accessToken(); });
        Future<String> b = pool.submit(() -> { TenantContext.setTenantId(1L); return instanceB.credentials(10L).accessToken(); });
        String tokenA = a.get();
        String tokenB = b.get();
        pool.shutdown();
        assertEquals(1, store.rotations, "compare-and-swap lets one rotation win");
        assertEquals(tokenA, tokenB, "the loser adopts the winner's token");
        assertEquals(cipher.decrypt(store.snapshot().accessEncrypted()), tokenA);
    }

    @Test
    void rejectedRefreshMarksReconnectNeededOnceAndIsNeverRetried() {
        InMemoryTokenStore store = store(NOW.minusSeconds(10), NOW.plusSeconds(86400));
        AtomicInteger calls = new AtomicInteger();
        FakeTransport transport = new FakeTransport(s -> {
            calls.incrementAndGet();
            return FakeTransport.apiError(36004001, "rt has expired");
        });
        TiktokTokenService service = service(store, transport);

        BusinessException first = assertThrows(BusinessException.class, () -> service.credentials(10L));
        assertEquals("TIKTOK_RECONNECT_REQUIRED", first.getErrorCode());
        assertEquals(ConnectionStatus.REFRESH_FAILED, store.snapshot().status());
        assertNull(store.snapshot().accessEncrypted());
        assertNull(store.snapshot().refreshEncrypted());
        assertEquals(1, calls.get());
        assertEquals(List.of("1:10:REFRESH_REJECTED"), notifications);

        for (int i = 0; i < 3; i++) {
            assertThrows(BusinessException.class, () -> service.credentials(10L));
        }
        assertEquals(1, calls.get(), "no retry after the platform refused");
        assertEquals(1, notifications.size(), "one alert only");
        assertEquals(1, store.failureMarks);
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("REFRESH_FAILED")), "a warning is logged");
    }

    @Test
    void refreshTokenPastItsExpiryNeedsReconnectWithoutCallingThePlatform() {
        InMemoryTokenStore store = store(NOW.minusSeconds(10), NOW.minusSeconds(1));
        FakeTransport transport = new FakeTransport(s -> fail("must not call the platform"));
        assertThrows(BusinessException.class, () -> service(store, transport).credentials(10L));
        assertEquals(ConnectionStatus.REFRESH_FAILED, store.snapshot().status());
        assertEquals(List.of("1:10:REFRESH_TOKEN_EXPIRED"), notifications);
    }

    @Test
    void networkOrServerFailureIsNotARejection() {
        InMemoryTokenStore store = store(NOW.minusSeconds(10), NOW.plusSeconds(86400));
        FakeTransport transport = new FakeTransport(s -> new TiktokHttpTransport.Response(503, ""));
        BusinessException e = assertThrows(BusinessException.class, () -> service(store, transport).credentials(10L));
        assertEquals("TIKTOK_UNAVAILABLE", e.getErrorCode());
        assertEquals(ConnectionStatus.CONNECTED, store.snapshot().status(), "connection untouched, may retry later");
        assertTrue(notifications.isEmpty());
    }

    @Test
    void aRejectionAfterAnotherInstanceAlreadyRotatedUsesTheirTokenInsteadOfFailing() {
        InMemoryTokenStore store = store(NOW.minusSeconds(10), NOW.plusSeconds(86400));
        FakeTransport transport = new FakeTransport(s -> {
            // The old single-use refresh token is refused because someone else just consumed it.
            store.externalRotation(cipher.encrypt("their-access"), cipher.encrypt("their-refresh"), NOW.plusSeconds(86400));
            return FakeTransport.apiError(36004001, "rt used");
        });
        assertEquals("their-access", service(store, transport).credentials(10L).accessToken());
        assertEquals(ConnectionStatus.CONNECTED, store.snapshot().status());
        assertTrue(notifications.isEmpty());
    }

    @Test
    void nonConnectedStatesNeverReachThePlatform() {
        for (ConnectionStatus status : List.of(ConnectionStatus.REVOKED, ConnectionStatus.DISCONNECTED,
                ConnectionStatus.MISSING_SCOPE, ConnectionStatus.REFRESH_FAILED)) {
            InMemoryTokenStore store = new InMemoryTokenStore(new TokenSnapshot(10L, 1L, status, "C", 1, null, null, null, null));
            FakeTransport transport = new FakeTransport(s -> fail("must not call the platform"));
            assertEquals("TIKTOK_RECONNECT_REQUIRED",
                    assertThrows(BusinessException.class, () -> service(store, transport).credentials(10L)).getErrorCode());
        }
    }

    @Test
    void anotherTenantCannotUseTheConnection() {
        InMemoryTokenStore store = store(NOW.plusSeconds(3600), NOW.plusSeconds(86400));
        TenantContext.setTenantId(2L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service(store, new FakeTransport(s -> fail("no call"))).credentials(10L));
        assertEquals("TIKTOK_CONNECTION_NOT_FOUND", e.getErrorCode());
        TenantContext.clear();
        assertEquals("TENANT_REQUIRED", assertThrows(BusinessException.class,
                () -> service(store, new FakeTransport(s -> fail("no call"))).credentials(10L)).getErrorCode());
    }

    @Test
    void noTokenOrSecretReachesTheLogs() {
        InMemoryTokenStore store = store(NOW.minusSeconds(10), NOW.plusSeconds(86400));
        FakeTransport transport = new FakeTransport(s -> FakeTransport.apiError(36004001, "rt has expired"));
        assertThrows(BusinessException.class, () -> service(store, transport).credentials(10L));
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            for (String secret : List.of("old-access", "old-refresh", "super-app-secret")) {
                assertFalse(text.contains(secret), "log leaked " + secret + ": " + text);
            }
        }
    }
}

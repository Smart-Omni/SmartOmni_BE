package com.smartomni.integration.service.catalog;

import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PriceChangePolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
    private CatalogMappingStore store;
    private PriceChangePolicy policy;

    @BeforeEach
    void setUp() {
        store = mock(CatalogMappingStore.class);
        policy = new PriceChangePolicy(store, BigDecimal.valueOf(50), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LinkRef link(String price, String currency) {
        return new LinkRef(5L, 7L, 3L, 1L, "S1", "P1", "SKU-1", price == null ? null : new BigDecimal(price), currency, null);
    }

    @Test
    void invalidPricesNeverOverwriteTheStoredPrice() {
        assertEquals(PriceChangePolicy.Result.INVALID_PRICE, policy.apply(link("100000", "VND"), null, "VND"));
        assertEquals(PriceChangePolicy.Result.INVALID_PRICE, policy.apply(link("100000", "VND"), BigDecimal.ZERO, "VND"));
        assertEquals(PriceChangePolicy.Result.INVALID_PRICE, policy.apply(link("100000", "VND"), new BigDecimal("-1"), "VND"));
        verifyNoMoreInteractions(store);
    }

    @Test
    void aDifferentCurrencyIsRejectedWithoutConversion() {
        assertEquals(PriceChangePolicy.Result.CURRENCY_MISMATCH, policy.apply(link("100000", "VND"), new BigDecimal("4"), "USD"));
        verifyNoMoreInteractions(store);
    }

    @Test
    void unchangedPriceOnlyRefreshesTheSyncTime() {
        assertEquals(PriceChangePolicy.Result.UNCHANGED, policy.apply(link("100000.00", "VND"), new BigDecimal("100000"), "VND"));
        verify(store).updateLinkPrice(5L, new BigDecimal("100000.00"), "VND", NOW);
        verify(store, never()).insertPriceHistory(anyLong(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void anOrdinaryChangeIsAppliedAndLogged() {
        assertEquals(PriceChangePolicy.Result.APPLIED, policy.apply(link("100000", "VND"), new BigDecimal("120000"), "VND"));
        verify(store).updateLinkPrice(5L, new BigDecimal("120000"), "VND", NOW);
        verify(store).insertPriceHistory(7L, 1L, "S1", new BigDecimal("100000"), new BigDecimal("120000"), "VND", false, true);
    }

    @Test
    void theFirstKnownPriceIsAppliedWithoutAnomalyCheck() {
        assertEquals(PriceChangePolicy.Result.APPLIED, policy.apply(link(null, null), new BigDecimal("1"), "VND"));
        verify(store).insertPriceHistory(7L, 1L, "S1", null, new BigDecimal("1"), "VND", false, true);
    }

    @Test
    void anAnomalyIsRecordedUnappliedAndTheStoredPriceStays() {
        assertEquals(PriceChangePolicy.Result.HELD_ANOMALY, policy.apply(link("200000", "VND"), new BigDecimal("10000"), "VND"));
        verify(store).insertPriceHistory(7L, 1L, "S1", new BigDecimal("200000"), new BigDecimal("10000"), "VND", true, false);
        verify(store).touchLink(5L, NOW);
        verify(store, never()).updateLinkPrice(anyLong(), any(), any(), any());
    }

    @Test
    void theSamePendingAnomalyIsNotRecordedTwice() {
        when(store.hasPendingAnomaly(7L, new BigDecimal("10000"))).thenReturn(true);
        assertEquals(PriceChangePolicy.Result.HELD_ANOMALY, policy.apply(link("200000", "VND"), new BigDecimal("10000"), "VND"));
        verify(store, never()).insertPriceHistory(anyLong(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void thresholdIsConfigurableAndZeroDisablesTheCheck() {
        PriceChangePolicy strict = new PriceChangePolicy(store, BigDecimal.valueOf(10), Clock.fixed(NOW, ZoneOffset.UTC));
        assertTrue(strict.isAnomaly(new BigDecimal("100"), new BigDecimal("111")));
        assertFalse(strict.isAnomaly(new BigDecimal("100"), new BigDecimal("110")), "exactly at the threshold is not an anomaly");
        PriceChangePolicy off = new PriceChangePolicy(store, BigDecimal.ZERO, Clock.fixed(NOW, ZoneOffset.UTC));
        assertFalse(off.isAnomaly(new BigDecimal("100"), new BigDecimal("1")));
        assertTrue(policy.isAnomaly(new BigDecimal("100"), new BigDecimal("160")), "increases count as well as drops");
    }
}

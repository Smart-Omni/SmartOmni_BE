package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TiktokStockPushServiceTest {
    @Mock TiktokStockLinkRepository links;
    @Mock TiktokCatalogClient catalog;

    @Test
    void pushesZeroToEveryConnectedListing() {
        when(links.findConnected(1, 9)).thenReturn(List.of(
                new TiktokStockLinkRepository.StockLink(4, "P-4", "S-4"),
                new TiktokStockLinkRepository.StockLink(5, "P-5", "S-5")));

        new TiktokStockPushService(links, catalog).push(1, 9, 0);

        verify(catalog).updateStock(4L, "P-4", "S-4", 0);
        verify(catalog).updateStock(5L, "P-5", "S-5", 0);
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void missingMappingFailsSoOutboxCanRetry() {
        var ex = assertThrows(BusinessException.class, () -> new TiktokStockPushService(links, catalog).push(1, 9, 5));
        assertEquals("TIKTOK_SKU_NOT_MAPPED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        verifyNoInteractions(catalog);
    }

    @Test
    void negativeStockIsAClientError() {
        var ex = assertThrows(BusinessException.class, () -> new TiktokStockPushService(links, catalog).push(1, 9, -1));
        assertEquals("TIKTOK_STOCK_INVALID", ex.getErrorCode());
        verifyNoInteractions(links, catalog);
    }

    @Test
    void platformRejectionPropagatesSoOutboxKeepsEventPending() {
        when(links.findConnected(1, 9)).thenReturn(List.of(new TiktokStockLinkRepository.StockLink(4, "P-4", "S-4")));
        doThrow(new TiktokApiException("TikTok rejected SKU inventory update", 200, 12052700, null, false))
                .when(catalog).updateStock(4L, "P-4", "S-4", 5);

        assertThrows(TiktokApiException.class, () -> new TiktokStockPushService(links, catalog).push(1, 9, 5));
        assertNull(TenantContext.getTenantId());
    }
}

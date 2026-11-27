package com.smartomni.integration.service.finance;

import com.smartomni.integration.client.shopee.ShopeeFinanceClient;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient.EscrowDetail;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.EnrichedReturnListResult;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnItem;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnListResult;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnSummary;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ShopeeReturnDetail;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.finance.ShopeeFinanceAndReturnsService.DisputeReturnRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ShopeeFinanceAndReturnsService Unit Tests")
class ShopeeFinanceAndReturnsServiceTest {

    private ShopeeFinanceClient financeClient;
    private ShopeeReturnsClient returnsClient;
    private ShopeeFinanceAndReturnsService service;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        financeClient = mock(ShopeeFinanceClient.class);
        returnsClient = mock(ShopeeReturnsClient.class);
        service = new ShopeeFinanceAndReturnsService(financeClient, returnsClient);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
    }

    @Test
    @DisplayName("getEscrowDetail forwards to ShopeeFinanceClient")
    void getEscrowDetail_Success() {
        EscrowDetail detail = new EscrowDetail(
                "241027SP1042", new BigDecimal("439500"), new BigDecimal("450000"),
                new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "ShopeePay"
        );
        when(financeClient.getEscrowDetail(88L, "241027SP1042")).thenReturn(detail);

        EscrowDetail result = service.getEscrowDetail(connection, "241027SP1042");

        assertNotNull(result);
        assertEquals(new BigDecimal("439500"), result.escrowAmount());
        verify(financeClient).getEscrowDetail(88L, "241027SP1042");
    }

    @Test
    @DisplayName("listReturns forwards to ShopeeReturnsClient")
    void listReturns_Success() {
        ReturnListResult listResult = new ReturnListResult(List.of(), false, 0);
        when(returnsClient.getReturnList(88L, 1, 20, "REQUESTED")).thenReturn(listResult);

        ReturnListResult result = service.listReturns(connection, 1, 20, "REQUESTED");

        assertNotNull(result);
        verify(returnsClient).getReturnList(88L, 1, 20, "REQUESTED");
    }

    @Test
    @DisplayName("getReturnDetail forwards to ShopeeReturnsClient")
    void getReturnDetail_Success() {
        ShopeeReturnDetail detail = new ShopeeReturnDetail(
                "RET-01", "SP01", "REQUESTED", BigDecimal.TEN, "VND", "Reason", "Text",
                "buyer", List.of(), List.of(), List.of(), BigDecimal.ZERO, ""
        );
        when(returnsClient.getReturnDetail(88L, "RET-01")).thenReturn(detail);

        ShopeeReturnDetail result = service.getReturnDetail(connection, "RET-01");

        assertNotNull(result);
        assertEquals("RET-01", result.returnSn());
    }

    @Test
    @DisplayName("confirmReturn forwards to ShopeeReturnsClient")
    void confirmReturn_Success() {
        when(returnsClient.confirmReturn(88L, "RET-01")).thenReturn(true);

        boolean ok = service.confirmReturn(connection, "RET-01");
        assertTrue(ok);
        verify(returnsClient).confirmReturn(88L, "RET-01");
    }

    @Test
    @DisplayName("disputeReturn forwards to ShopeeReturnsClient with dispute request parameters")
    void disputeReturn_Success() {
        when(returnsClient.disputeReturn(eq(88L), eq("RET-01"), eq(1), eq("Text"), eq("email@test.com"), any()))
                .thenReturn(true);

        DisputeReturnRequest req = DisputeReturnRequest.builder()
                .disputeReasonId(1)
                .disputeTextReason("Text")
                .email("email@test.com")
                .proofImages(List.of("http://img.jpg"))
                .build();

        boolean ok = service.disputeReturn(connection, "RET-01", req);
        assertTrue(ok);
        verify(returnsClient).disputeReturn(88L, "RET-01", 1, "Text", "email@test.com", List.of("http://img.jpg"));
    }

    @Test
    @DisplayName("offerReturn forwards to ShopeeReturnsClient")
    void offerReturn_Success() {
        when(returnsClient.offerReturn(88L, "RET-01", BigDecimal.valueOf(100000), "Partial")).thenReturn(true);

        ShopeeFinanceAndReturnsService.OfferReturnRequest req = ShopeeFinanceAndReturnsService.OfferReturnRequest.builder()
                .offerAmount(BigDecimal.valueOf(100000))
                .offerReason("Partial")
                .build();

        assertTrue(service.offerReturn(connection, "RET-01", req));
        verify(returnsClient).offerReturn(88L, "RET-01", BigDecimal.valueOf(100000), "Partial");
    }

    @Test
    @DisplayName("listEnrichedReturns merges summary and detail fields")
    void listEnrichedReturns_Success() {
        ReturnSummary summary = new ReturnSummary(
                "RET-01", "SP01", "REQUESTED", BigDecimal.valueOf(300000), "VND",
                "CHANGE_OF_MIND", "Đổi ý", null, List.of(), true, "PENDING_BUYER_RESPOND",
                "COMPENSATION_NOT_APPLICABLE", BigDecimal.ZERO, null, null, null, null, true,
                1714100000L, 1714100500L, "SPX01", List.of(), List.of(), "seller_validation",
                null, BigDecimal.valueOf(150000), "Giảm 150k"
        );
        ShopeeReturnDetail detail = new ShopeeReturnDetail(
                "RET-01", "SP01", "REQUESTED", BigDecimal.valueOf(300000), "VND", "CHANGE_OF_MIND", "Đổi ý",
                "buyer_01", List.of(new ReturnItem(1L, 2L, "Áo thun", 1, BigDecimal.valueOf(300000), BigDecimal.valueOf(300000), "SKU-1")),
                List.of("https://img/1.jpg"), List.of(), BigDecimal.ZERO, "Chờ phản hồi"
        );
        when(returnsClient.getReturnList(88L, 1, 50, null)).thenReturn(new ReturnListResult(List.of(summary), false, 1));
        when(returnsClient.getReturnDetail(88L, "RET-01")).thenReturn(detail);

        EnrichedReturnListResult res = service.listEnrichedReturns(connection, 1, 50, null);

        assertEquals(1, res.returnList().size());
        assertEquals("buyer_01", res.returnList().get(0).buyerUsername());
        assertEquals("PENDING_BUYER_RESPOND", res.returnList().get(0).negotiationStatus());
        assertEquals(BigDecimal.valueOf(150000), res.returnList().get(0).offerAmount());
        assertEquals(1, res.returnList().get(0).items().size());
    }
}

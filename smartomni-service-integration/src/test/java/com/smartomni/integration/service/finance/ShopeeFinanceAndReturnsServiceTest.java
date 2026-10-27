package com.smartomni.integration.service.finance;

import com.smartomni.integration.client.shopee.ShopeeFinanceClient;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient.EscrowDetail;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient;
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
}

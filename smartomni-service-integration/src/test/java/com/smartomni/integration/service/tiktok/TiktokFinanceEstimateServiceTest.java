package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokEscrowDetail;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokSettlementListResult;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiktokFinanceEstimateServiceTest {

    private final TiktokOrderClient orderClient = mock(TiktokOrderClient.class);
    private final TiktokFinanceEstimateService service = new TiktokFinanceEstimateService(orderClient);

    private static TiktokOrderDetailResponse order(String id, String status, String sub, String seller, String platform,
                                                    String ship, String tax, String total) {
        return TiktokOrderDetailResponse.builder().orderId(id).orderStatus(status).updateTime(1_800_000_000L)
                .payment(TiktokOrderDetailResponse.Payment.builder().currency("VND")
                        .subTotal(new BigDecimal(sub)).sellerDiscount(new BigDecimal(seller))
                        .platformDiscount(new BigDecimal(platform)).shippingFee(new BigDecimal(ship))
                        .tax(new BigDecimal(tax)).totalAmount(new BigDecimal(total)).build())
                .build();
    }

    @Test
    void sumsCompletedOrdersAcrossTwoPagesAndIgnoresOthers() {
        when(orderClient.searchOrders(eq(7L), argThat((OrderSearchRequest r) -> r != null && r.getPageToken() == null)))
                .thenReturn(OrderSearchResponse.builder().nextPageToken("P2").orders(List.of(
                        order("O1", "COMPLETED", "100000", "10000", "5000", "20000", "0", "120000"),
                        order("O2", "CANCELLED", "999999", "0", "0", "0", "0", "999999"))).build());
        when(orderClient.searchOrders(eq(7L), argThat((OrderSearchRequest r) -> r != null && "P2".equals(r.getPageToken()))))
                .thenReturn(OrderSearchResponse.builder().nextPageToken("").orders(List.of(
                        order("O3", "COMPLETED", "50000", "0", "0", "15000", "1000", "66000"))).build());

        TiktokSettlementListResult result = service.estimate(7L, 1_700_000_000L, 1_900_000_000L);

        assertTrue(result.estimated());
        assertEquals(TiktokFinanceClient.SOURCE_ESTIMATED, result.source());
        assertEquals(2, result.totals().orderCount());
        assertEquals(new BigDecimal("150000"), result.totals().subTotal());
        assertEquals(new BigDecimal("10000"), result.totals().sellerDiscount());
        assertEquals(new BigDecimal("5000"), result.totals().platformDiscount());
        assertEquals(new BigDecimal("35000"), result.totals().shippingFee());
        assertEquals(new BigDecimal("1000"), result.totals().tax());
        assertEquals(new BigDecimal("186000"), result.totals().totalAmount());
        assertEquals(List.of("O1", "O3"), result.settlements().stream().map(s -> s.statementId()).toList());

        ArgumentCaptor<OrderSearchRequest> request = ArgumentCaptor.forClass(OrderSearchRequest.class);
        verify(orderClient, times(2)).searchOrders(eq(7L), request.capture());
        assertEquals("COMPLETED", request.getAllValues().get(0).getOrderStatus());
        assertEquals(1_700_000_000L, request.getAllValues().get(0).getUpdateTimeFrom());
        assertEquals(1_900_000_000L, request.getAllValues().get(0).getUpdateTimeTo());
    }

    @Test
    void invalidRangeIs400() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.estimate(7L, 200L, 100L));
        assertEquals("INVALID_DATE_RANGE", ex.getErrorCode());
        verifyNoInteractions(orderClient);
    }

    @Test
    void escrowReadsPriceDetailIncludingLineItems() throws Exception {
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        ObjectMapper mapper = new ObjectMapper();
        when(executor.call(1L, "GET", "/order/202407/orders/ORD-1/price_detail", Map.of(), null)).thenReturn(mapper.readTree("""
                {"currency":"VND","sku_list_price":"260000","subtotal_deduction_seller":"20000","subtotal_deduction_platform":"0",
                 "subtotal":"240000","shipping_list_price":"30000","shipping_fee_deduction_seller":"5000","shipping_sale_price":"25000",
                 "tax_amount":"0","payment":"265000","total":"290000",
                 "line_items":[{"id":"OL-1","sku_list_price":"260000","payment":"265000"}]}
                """));

        TiktokEscrowDetail detail = new TiktokFinanceClient(executor, mapper).getEscrowDetail(1L, "ORD-1");

        assertEquals("ORDER_PRICE_DETAIL", detail.source());
        assertEquals(new BigDecimal("265000"), detail.buyerTotalAmount());
        assertEquals(new BigDecimal("260000"), detail.orderOriginalPrice());
        assertEquals(new BigDecimal("20000"), detail.sellerDiscount());
        assertEquals(new BigDecimal("25000"), detail.shippingFee());
        assertEquals(new BigDecimal("290000"), detail.order().total());
        assertEquals("OL-1", detail.lineItems().get(0).id());
        assertNull(detail.escrowAmount(), "no finance statement: unknown, not zero");
    }

    @Test
    void escrowRejectsMalformedOrderIds() {
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new TiktokFinanceClient(executor, new ObjectMapper()).getEscrowDetail(1L, "../x"));
        assertEquals("TIKTOK_ORDER_ID_INVALID", ex.getErrorCode());
        verifyNoInteractions(executor);
    }
}

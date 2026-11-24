package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.tiktok.CancellationSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.TiktokCancellationResponse;
import com.smartomni.integration.dto.tiktok.TiktokRefundAmount;
import com.smartomni.integration.dto.tiktok.TiktokReturnRecord;
import com.smartomni.integration.dto.tiktok.TiktokReturnResponse;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Return and cancellation clients against payloads shaped like the simulator's (aftersale.mapper.ts). */
class TiktokAftersaleClientsTest {

    private static final String RETURN_JSON = """
            {"next_page_token":"","total_count":1,"return_orders":[{
              "return_id":"RET-1","order_id":"ORD-1","return_status":"RETURN_OR_REFUND_REQUEST_PENDING",
              "return_type":"RETURN_AND_REFUND","role":"BUYER","return_reason":"DAMAGED_ITEM",
              "return_reason_text":"Damaged item","arbitration_status":"NO_ARBITRATION",
              "create_time":1800000000,"update_time":1800000100,
              "refund_amount":{"currency":"VND","refund_subtotal":"150000","refund_shipping_fee":"20000","refund_tax":"0","refund_total":"170000"},
              "return_line_items":[{"return_line_item_id":"RL-1","order_line_item_id":"OL-1","product_name":"Ao",
                "sku_id":"SKU-1","sku_name":"M","seller_sku":"AO-M",
                "refund_amount":{"currency":"VND","refund_subtotal":"150000","refund_shipping_fee":"0","refund_tax":"0","refund_total":"150000"}}]}]}
            """;

    private static final String CANCEL_JSON = """
            {"next_page_token":"","total_count":1,"cancellations":[{
              "cancel_id":"CNL-1","order_id":"ORD-2","cancel_status":"CANCELLATION_REQUEST_PENDING","cancel_type":"CANCEL",
              "role":"BUYER","cancel_reason":"CHANGED_MIND","create_time":1800000000,"update_time":1800000050,
              "refund_amount":{"currency":"VND","refund_subtotal":"90000","refund_shipping_fee":"10000","refund_tax":"0","refund_total":"100000"},
              "cancel_line_items":[{"cancel_line_item_id":"CL-1","order_line_item_id":"OL-9","sku_id":"SKU-9","seller_sku":"X",
                "refund_amount":{"currency":"VND","refund_subtotal":"90000","refund_shipping_fee":"0","refund_tax":"0","refund_total":"90000"}}]}]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
    private final TiktokReturnClient returns = new TiktokReturnClient(executor, mapper);
    private final TiktokCancellationClient cancellations = new TiktokCancellationClient(executor, mapper);

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void returnDetailUsesSearchByIdAndParsesRefundObjects() throws Exception {
        when(executor.call(eq(1L), eq("POST"), eq(TiktokReturnClient.SEARCH_RETURNS_PATH), anyMap(), anyString()))
                .thenReturn(json(RETURN_JSON));

        TiktokReturnResponse ret = returns.getReturnDetail(1L, "RET-1");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(executor).call(eq(1L), eq("POST"), eq(TiktokReturnClient.SEARCH_RETURNS_PATH), anyMap(), body.capture());
        assertEquals("RET-1", json(body.getValue()).path("return_ids").get(0).asText());
        assertEquals(new BigDecimal("170000"), ret.getRefundAmount().total());
        assertEquals(new BigDecimal("20000"), ret.getRefundAmount().shippingFee());
        assertEquals("BUYER", ret.getRole());
        assertEquals("NO_ARBITRATION", ret.getArbitrationStatus());
        assertEquals("RL-1", ret.getReturnLineItems().get(0).getReturnItemId());
        assertEquals(new BigDecimal("150000"), TiktokRefundAmount.totalOf(ret.getReturnLineItems().get(0).getRefundAmount()));
    }

    @Test
    void returnDetailIsNullWhenTiktokDoesNotReturnTheId() throws Exception {
        when(executor.call(eq(1L), eq("POST"), anyString(), anyMap(), anyString()))
                .thenReturn(json("{\"return_orders\":[]}"));
        assertNull(returns.getReturnDetail(1L, "RET-404"));
    }

    @Test
    void returnRecordsAreParsed() throws Exception {
        when(executor.call(1L, "GET", "/return_refund/202309/returns/RET-1/records", Map.of(), null))
                .thenReturn(json("{\"records\":[{\"create_time\":1800000000,\"description\":\"NEW -> RETURN_OR_REFUND_REQUEST_PENDING\","
                        + "\"event\":\"ORDER_RETURN\",\"note\":\"n\",\"reason_text\":\"n\",\"role\":\"BUYER\"}]}"));

        List<TiktokReturnRecord> records = returns.getReturnRecords(1L, "RET-1");

        assertEquals(1, records.size());
        assertEquals("ORDER_RETURN", records.get(0).event());
        assertEquals("BUYER", records.get(0).role());
        assertEquals(1_800_000_000L, records.get(0).createTime());
    }

    @Test
    void returnSearchFiltersByUpdateTimeAndClampsPageSize() throws Exception {
        when(executor.call(eq(1L), eq("POST"), anyString(), anyMap(), anyString())).thenReturn(json("{}"));

        returns.searchReturns(1L, ReturnSearchRequest.builder().pageSize(5).updateTimeFrom(100L).updateTimeTo(200L).build());
        returns.searchReturns(1L, ReturnSearchRequest.builder().pageSize(500).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> query = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).call(eq(1L), eq("POST"), anyString(), query.capture(), body.capture());
        assertEquals("10", query.getAllValues().get(0).get("page_size"));
        assertEquals("50", query.getAllValues().get(1).get("page_size"));
        assertEquals(100, json(body.getAllValues().get(0)).path("update_time_ge").asLong());
        assertEquals(200, json(body.getAllValues().get(0)).path("update_time_lt").asLong());
    }

    @Test
    void cancellationsParseRefundObjectsAndLineItems() throws Exception {
        when(executor.call(eq(1L), eq("POST"), eq(TiktokCancellationClient.SEARCH_CANCELLATIONS_PATH), anyMap(), anyString()))
                .thenReturn(json(CANCEL_JSON));

        TiktokCancellationResponse c = cancellations.getCancellation(1L, "CNL-1");

        assertEquals(new BigDecimal("100000"), c.getRefundAmount().total());
        assertEquals("BUYER", c.getRole());
        assertEquals("OL-9", c.getCancelLineItems().get(0).getOrderLineItemId());
        assertEquals(new BigDecimal("90000"), c.getCancelLineItems().get(0).getRefundAmount().total());
    }

    @Test
    void cancellationSearchFiltersByUpdateTimeAndClampsPageSize() throws Exception {
        when(executor.call(eq(1L), eq("POST"), anyString(), anyMap(), anyString())).thenReturn(json("{}"));

        cancellations.searchCancellations(1L, CancellationSearchRequest.builder().pageSize(0)
                .updateTimeFrom(100L).updateTimeTo(200L).cancelIds(List.of("CNL-1")).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> query = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(executor).call(eq(1L), eq("POST"), anyString(), query.capture(), body.capture());
        assertEquals("1", query.getValue().get("page_size"));
        JsonNode sent = json(body.getValue());
        assertEquals(100, sent.path("update_time_ge").asLong());
        assertEquals(200, sent.path("update_time_lt").asLong());
        assertEquals("CNL-1", sent.path("cancel_ids").get(0).asText());
    }

    @Test
    void sellerCancelUsesTheCancellationsApi() throws Exception {
        when(executor.call(eq(1L), eq("POST"), eq("/return_refund/202309/cancellations"), anyMap(), anyString()))
                .thenReturn(json("{\"cancel_id\":\"CNL-7\",\"cancel_status\":\"CANCELLATION_REQUEST_COMPLETE\"}"));

        var result = cancellations.cancelOrder(1L, "ORD-7", "OUT_OF_STOCK", List.of("OL-1"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(executor).call(eq(1L), eq("POST"), eq("/return_refund/202309/cancellations"), anyMap(), body.capture());
        JsonNode sent = json(body.getValue());
        assertEquals("ORD-7", sent.path("order_id").asText());
        assertEquals("OUT_OF_STOCK", sent.path("cancel_reason").asText());
        assertEquals("OL-1", sent.path("order_line_item_ids").get(0).asText());
        assertEquals("CNL-7", result.cancelId());
        assertEquals("CANCELLATION_REQUEST_COMPLETE", result.cancelStatus());
        verify(executor, never()).call(anyLong(), anyString(), startsWith("/order/"), anyMap(), any());
    }

    @Test
    void approveAndRejectUseTheirPaths() throws Exception {
        when(executor.call(eq(1L), eq("POST"), anyString(), anyMap(), anyString())).thenReturn(json("{}"));

        assertTrue(returns.approveReturn(1L, "RET-1"));
        assertTrue(returns.rejectReturn(1L, "RET-1", "ITEM_AS_DESCRIBED"));
        assertTrue(cancellations.approveCancellation(1L, "CNL-1"));
        assertTrue(cancellations.rejectCancellation(1L, "CNL-1", "ALREADY_SHIPPED"));

        verify(executor).call(eq(1L), eq("POST"), eq("/return_refund/202309/returns/RET-1/approve"), anyMap(), anyString());
        verify(executor).call(eq(1L), eq("POST"), eq("/return_refund/202309/returns/RET-1/reject"), anyMap(), anyString());
        verify(executor).call(eq(1L), eq("POST"), eq("/return_refund/202309/cancellations/CNL-1/approve"), anyMap(), anyString());
        verify(executor).call(eq(1L), eq("POST"), eq("/return_refund/202309/cancellations/CNL-1/reject"), anyMap(), anyString());
    }

    @Test
    void scalarRefundAmountIsStillReadAsTotal() throws Exception {
        TiktokReturnResponse legacy = mapper.treeToValue(json("{\"return_id\":\"R\",\"refund_amount\":\"5000\"}"), TiktokReturnResponse.class);
        assertEquals(new BigDecimal("5000"), legacy.getRefundAmount().total());
        TiktokReturnResponse numeric = mapper.treeToValue(json("{\"return_id\":\"R\",\"refund_amount\":5000}"), TiktokReturnResponse.class);
        assertEquals(new BigDecimal("5000"), numeric.getRefundAmount().total());
    }
}

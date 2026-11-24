package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiktokVoucherClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
    private final TiktokVoucherClient client = new TiktokVoucherClient(executor, mapper);

    private JsonNode page(int from, int count, String next) throws Exception {
        String coupons = IntStream.range(from, from + count)
                .mapToObj(i -> "{\"id\":\"C" + i + "\",\"title\":\"Loyalty Diamond 30%\",\"status\":\"ONGOING\",\"promo_code\":\"P" + i
                        + "\",\"discount\":{\"type\":\"PERCENT_OFF\",\"percentage\":\"30\"}}")
                .collect(Collectors.joining(","));
        return mapper.readTree("{\"coupons\":[" + coupons + "],\"next_page_token\":\"" + next + "\"}");
    }

    @Test
    void sixtyCodesAcrossTwoPagesWithTitleKeywordAndStatus() throws Exception {
        when(executor.call(eq(1L), eq("POST"), eq(TiktokVoucherClient.SEARCH_COUPONS_PATH),
                argThat(q -> q != null && !q.containsKey("page_token")), anyString())).thenReturn(page(0, 50, "T2"));
        when(executor.call(eq(1L), eq("POST"), eq(TiktokVoucherClient.SEARCH_COUPONS_PATH),
                argThat(q -> q != null && "T2".equals(q.get("page_token"))), anyString())).thenReturn(page(50, 10, ""));

        List<TiktokVoucherDto> codes = client.searchCoupons(1L, "Loyalty Diamond 30%", List.of("ONGOING"));

        assertEquals(60, codes.size());
        assertEquals("P59", codes.get(59).getVoucherCode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> query = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).call(eq(1L), eq("POST"), anyString(), query.capture(), body.capture());
        assertEquals("100", query.getAllValues().get(0).get("page_size"));
        JsonNode sent = mapper.readTree(body.getAllValues().get(0));
        assertEquals("Loyalty Diamond 30%", sent.path("title_keyword").asText());
        assertEquals("ONGOING", sent.path("status").get(0).asText());
    }

    @Test
    void repeatedPageTokenStops() throws Exception {
        when(executor.call(eq(1L), eq("POST"), anyString(), anyMap(), anyString())).thenReturn(page(0, 1, "SAME"));

        assertThrows(TiktokApiException.class, () -> client.searchCoupons(1L));
    }

    @Test
    void couponDetailReadsUsageStats() throws Exception {
        when(executor.call(1L, "GET", "/promotion/202406/coupons/C1", Map.of(), null)).thenReturn(mapper.readTree(
                "{\"coupon\":{\"id\":\"C1\",\"status\":\"ONGOING\",\"promo_code\":\"P1\",\"usage_stats\":{\"redeemed_count\":1},"
                        + "\"discount\":{\"type\":\"PERCENT_OFF\",\"percentage\":\"30\"}}}"));

        TiktokVoucherDto coupon = client.getCoupon(1L, "C1");

        assertEquals(1, coupon.getRedeemedCount());
        assertEquals("ONGOING", coupon.getStatus());
    }
}

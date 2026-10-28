package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import com.smartomni.integration.service.shopee.ShopeeTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ShopeeFinanceClient Unit Tests")
class ShopeeFinanceClientTest {

    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private FakeShopeeTransport transport;
    private ShopeeFinanceClient client;

    @BeforeEach
    void setUp() {
        ShopeeProperties properties = new ShopeeProperties();
        properties.setPartnerId(1001141L);
        properties.setPartnerKey("shopee_mock_secret_key_2026");
        properties.setBaseUrl("http://shopee-simulator.test:8080");

        ShopeeTokenService tokenService = mock(ShopeeTokenService.class);
        when(tokenService.credentials(anyLong()))
                .thenReturn(new ShopeeTokenService.Credentials("AT-valid-123", 727720655L, 1L));

        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        transport = new FakeShopeeTransport(s -> {
            String path = s.uri().getPath();
            if (path.contains("/payment/get_escrow_detail")) {
                return FakeShopeeTransport.ok("""
                    {
                      "order_income": {
                        "escrow_amount": 439500,
                        "buyer_total_amount": 450000,
                        "order_original_price": 500000,
                        "shopee_shipping_rebate": 30000,
                        "commission_fee": 18000,
                        "service_fee": 13500,
                        "seller_transaction_fee": 9000,
                        "reverse_shipping_fee": 0,
                        "seller_return_refund": 0,
                        "buyer_payment_method": "ShopeePay"
                      }
                    }
                    """);
            }
            return FakeShopeeTransport.ok("{}");
        });

        ShopeeShopApiExecutor executor = new ShopeeShopApiExecutor(tokenService, properties, transport, clock, mapper);
        client = new ShopeeFinanceClient(executor, mapper);
    }

    @Test
    @DisplayName("getEscrowDetail parses financial breakdown and enforces non-negative escrow")
    void getEscrowDetail_Success() {
        ShopeeFinanceClient.EscrowDetail detail = client.getEscrowDetail(88L, "241027SP1042");

        assertNotNull(detail);
        assertEquals("241027SP1042", detail.orderSn());
        assertEquals(BigDecimal.valueOf(439500.0), detail.escrowAmount());
        assertEquals(BigDecimal.valueOf(450000.0), detail.buyerTotalAmount());
        assertEquals(BigDecimal.valueOf(500000.0), detail.orderOriginalPrice());
        assertEquals(BigDecimal.valueOf(30000.0), detail.shopeeShippingRebate());
        assertEquals(BigDecimal.valueOf(18000.0), detail.commissionFee());
        assertEquals(BigDecimal.valueOf(13500.0), detail.serviceFee());
        assertEquals(BigDecimal.valueOf(9000.0), detail.sellerTransactionFee());
        assertEquals("ShopeePay", detail.buyerPaymentMethod());

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/payment/get_escrow_detail", sent.uri().getPath());
        String q = URLDecoder.decode(sent.uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(q.contains("order_sn=241027SP1042"));
    }
}

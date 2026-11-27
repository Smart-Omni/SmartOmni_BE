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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ShopeeReturnsClient Unit Tests")
class ShopeeReturnsClientTest {

    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private FakeShopeeTransport transport;
    private ShopeeReturnsClient client;

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
            if (path.contains("/returns/get_return_list")) {
                return FakeShopeeTransport.ok("""
                    {
                      "more": false,
                      "total": 1,
                      "return_list": [
                        {
                          "return_sn": "RET-241028-001",
                          "order_sn": "241027SP1042",
                          "status": "REQUESTED",
                          "refund_amount": 225000,
                          "currency": "VND",
                          "reason": "DEFECTIVE_PRODUCT",
                          "text_reason": "Mouse scroll wheel broken",
                          "due_date": 1714200000,
                          "needs_logistics": true,
                          "negotiation_status": "PENDING_BUYER_RESPOND",
                          "seller_compensation_status": "COMPENSATION_NOT_APPLICABLE",
                          "reverse_shipping_fee": 25000,
                          "dispute_due_date": 1714372800,
                          "buyer_return_due_date": 1714718400,
                          "expected_return_date": 1714459200,
                          "reverse_delivered_at": 1714200100,
                          "is_nfr_exempt": true,
                          "image": ["http://img.shopee.vn/proof1.jpg"]
                        }
                      ]
                    }
                    """);
            }
            if (path.contains("/returns/get_return_detail")) {
                return FakeShopeeTransport.ok("""
                    {
                      "return_sn": "RET-241028-001",
                      "order_sn": "241027SP1042",
                      "status": "REQUESTED",
                      "refund_amount": 225000,
                      "currency": "VND",
                      "reason": "DEFECTIVE_PRODUCT",
                      "text_reason": "Mouse scroll wheel broken",
                      "user": { "username": "nguyen_buyer" },
                      "item": [
                        {
                          "item_id": 1001,
                          "model_id": 9001,
                          "name": "Wireless Mouse Black",
                          "amount": 1,
                          "item_price": 225000,
                          "refund_amount": 225000
                        }
                      ],
                      "proof_images": ["http://img.shopee.vn/proof1.jpg"],
                      "seller_proof_images": [],
                      "reverse_shipping_fee": 0,
                      "ruling_notes": "",
                      "needs_logistics": true,
                      "compensation_status": "COMPENSATION_NOT_APPLICABLE",
                      "dispute_due_date": 1714372800,
                      "buyer_return_due_date": 1714718400,
                      "expected_return_date": 1714459200,
                      "reverse_delivered_at": 1714200100,
                      "is_nfr_exempt": true,
                      "tracking_number": "SPXRET001",
                      "negotiation_status": "PENDING_BUYER_RESPOND",
                      "offer_amount": 150000
                    }
                    """);
            }
            if (path.contains("/returns/get_return_dispute_reason")) {
                return FakeShopeeTransport.ok("""
                    {
                      "dispute_reason_list": [
                        { "dispute_reason": 50, "dispute_name": "INCORRECT_CLAIM", "dispute_requirement": "Provide unboxing proof" }
                      ]
                    }
                    """);
            }
            if (path.contains("/returns/offer")) {
                return FakeShopeeTransport.ok("{\"return_sn\":\"RET-241028-001\",\"offer_amount\":100000}");
            }
            if (path.contains("/returns/confirm")) {
                return FakeShopeeTransport.ok("{\"return_sn\":\"RET-241028-001\"}");
            }
            if (path.contains("/returns/dispute")) {
                return FakeShopeeTransport.ok("{\"return_sn\":\"RET-241028-001\"}");
            }
            return FakeShopeeTransport.ok("{}");
        });

        ShopeeShopApiExecutor executor = new ShopeeShopApiExecutor(tokenService, properties, transport, clock, mapper);
        client = new ShopeeReturnsClient(executor, mapper);
    }

    @Test
    @DisplayName("getReturnList retrieves and parses return requests")
    void getReturnList_Success() {
        ShopeeReturnsClient.ReturnListResult result = client.getReturnList(88L, 1, 20, "REQUESTED");

        assertFalse(result.more());
        assertEquals(1, result.total());
        assertEquals(1, result.returnList().size());

        ShopeeReturnsClient.ReturnSummary r = result.returnList().get(0);
        assertEquals("RET-241028-001", r.returnSn());
        assertEquals("241027SP1042", r.orderSn());
        assertEquals("REQUESTED", r.status());
        assertEquals(BigDecimal.valueOf(225000.0), r.refundAmount());
        assertEquals("DEFECTIVE_PRODUCT", r.reason());
        assertEquals(1, r.proofImages().size());
        assertEquals("http://img.shopee.vn/proof1.jpg", r.proofImages().get(0));
        assertEquals(Boolean.TRUE, r.needsLogistics());
        assertEquals("PENDING_BUYER_RESPOND", r.negotiationStatus());
        assertEquals("COMPENSATION_NOT_APPLICABLE", r.sellerCompensationStatus());
        assertEquals(1714372800L, r.disputeDueDate());
        assertEquals(1714718400L, r.buyerReturnDueDate());
        assertEquals(1714459200L, r.expectedReturnDate());
        assertEquals(1714200100L, r.reverseDeliveredAt());
        assertEquals(Boolean.TRUE, r.isNfrExempt());

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/returns/get_return_list", sent.uri().getPath());
        String q = URLDecoder.decode(sent.uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(q.contains("page_no=1"));
        assertTrue(q.contains("status=REQUESTED"));
    }

    @Test
    @DisplayName("getReturnDetail retrieves full detail and item list")
    void getReturnDetail_Success() {
        ShopeeReturnsClient.ShopeeReturnDetail detail = client.getReturnDetail(88L, "RET-241028-001");

        assertNotNull(detail);
        assertEquals("RET-241028-001", detail.returnSn());
        assertEquals("nguyen_buyer", detail.buyerUsername());
        assertEquals(1, detail.items().size());
        assertEquals("Wireless Mouse Black", detail.items().get(0).name());
        assertEquals(1, detail.buyerProofImages().size());
        assertEquals(Boolean.TRUE, detail.needsLogistics());
        assertEquals("COMPENSATION_NOT_APPLICABLE", detail.compensationStatus());
        assertEquals(1714372800L, detail.disputeDueDate());
        assertEquals(1714718400L, detail.buyerReturnDueDate());
        assertEquals(1714459200L, detail.expectedReturnDate());
        assertEquals(1714200100L, detail.reverseDeliveredAt());
        assertEquals(Boolean.TRUE, detail.isNfrExempt());
        assertEquals("SPXRET001", detail.trackingNumber());
        assertEquals("PENDING_BUYER_RESPOND", detail.negotiationStatus());
        assertEquals(BigDecimal.valueOf(150000.0), detail.offerAmount());
    }

    @Test
    @DisplayName("getReturnDisputeReasons retrieves Shopee dispute reason options")
    void getReturnDisputeReasons_Success() {
        List<ShopeeReturnsClient.DisputeReasonOption> reasons = client.getReturnDisputeReasons(88L, "RET-241028-001");

        assertEquals(1, reasons.size());
        assertEquals(50, reasons.get(0).disputeReasonId());
        assertEquals("INCORRECT_CLAIM", reasons.get(0).disputeName());
    }

    @Test
    @DisplayName("offerReturn sends POST with return_sn, offer_amount, and offer_reason")
    void offerReturn_Success() {
        boolean ok = client.offerReturn(88L, "RET-241028-001", BigDecimal.valueOf(100000), "Partial refund 100k");

        assertTrue(ok);
        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/returns/offer", sent.uri().getPath());
        assertTrue(sent.body().contains("\"return_sn\":\"RET-241028-001\""));
        assertTrue(sent.body().contains("\"offer_amount\":100000"));
        assertTrue(sent.body().contains("\"offer_reason\":\"Partial refund 100k\""));
    }

    @Test
    @DisplayName("confirmReturn sends POST with return_sn")
    void confirmReturn_Success() {
        boolean ok = client.confirmReturn(88L, "RET-241028-001");

        assertTrue(ok);
        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/returns/confirm", sent.uri().getPath());
        assertTrue(sent.body().contains("\"return_sn\":\"RET-241028-001\""));
    }

    @Test
    @DisplayName("disputeReturn sends POST with reason id, text, and proof images")
    void disputeReturn_Success() {
        boolean ok = client.disputeReturn(88L, "RET-241028-001", 1, "Product was tested functional", "seller@smartomni.com", List.of("http://proof.jpg"));

        assertTrue(ok);
        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/returns/dispute", sent.uri().getPath());
        assertTrue(sent.body().contains("\"return_sn\":\"RET-241028-001\""));
        assertTrue(sent.body().contains("\"dispute_reason_id\":1"));
        assertTrue(sent.body().contains("\"dispute_text_reason\":\"Product was tested functional\""));
        assertTrue(sent.body().contains("\"image\":[\"http://proof.jpg\"]"));
    }
}

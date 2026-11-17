package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokReturnClient;
import com.smartomni.integration.dto.tiktok.TiktokAftersaleCodesDto;
import com.smartomni.integration.dto.tiktok.TiktokAftersaleCodesDto.ReasonCodeItem;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokAftersaleCodesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("D9: TiktokAftersaleController & Codes Service Unit Tests")
class TiktokAftersaleControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TiktokTenantConnections tenantConnections;
    private TiktokReturnClient returnClient;
    private TiktokAftersaleCodesService service;
    private TiktokAftersaleController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        returnClient = mock(TiktokReturnClient.class);
        service = new TiktokAftersaleCodesService(tenantConnections, returnClient);
        controller = new TiktokAftersaleController(service);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(10L);
        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("GET /api/v1/tiktok/aftersale/reasons returns full D9 catalog")
    void getAllReasons_ReturnsFullCatalog() {
        ResponseEntity<ApiResponse<TiktokAftersaleCodesDto>> res = controller.getAllReasons();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        TiktokAftersaleCodesDto data = res.getBody().getData();
        assertNotNull(data);
        assertFalse(data.getBuyerCancelReasons().isEmpty());
        assertFalse(data.getSellerCancelReasons().isEmpty());
        assertFalse(data.getReturnRequestReasons().isEmpty());
        assertEquals(5, data.getRejectReasons().size());
        assertEquals(8, data.getReturnStatuses().size());
        assertEquals(4, data.getCancelStatuses().size());
    }

    @Test
    @DisplayName("GET /api/v1/tiktok/aftersale/reject-reasons calls Simulator when returnOrCancelId is passed")
    void getRejectReasons_WithId_CallsSimulator() throws Exception {
        JsonNode simNode = mapper.readTree("""
                {
                  "reasons": [
                    { "name": "ITEM_AS_DESCRIBED", "text": "The item matches its description" }
                  ]
                }
                """);
        when(returnClient.getRejectReasons(77L, "RET-001")).thenReturn(simNode);

        ResponseEntity<ApiResponse<List<ReasonCodeItem>>> res = controller.getRejectReasons("RET-001");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1, res.getBody().getData().size());
        assertEquals("ITEM_AS_DESCRIBED", res.getBody().getData().get(0).getName());
        assertEquals("Sản phẩm đúng như mô tả", res.getBody().getData().get(0).getTextVi());
    }

    @Test
    @DisplayName("GET /api/v1/tiktok/aftersale/eligibility/{orderId} proxies Simulator eligibility")
    void getEligibility_Success() throws Exception {
        JsonNode simNode = mapper.readTree("""
                { "request_types": ["REFUND", "RETURN_AND_REFUND"], "sku_eligibility": [] }
                """);
        when(returnClient.getAftersaleEligibility(77L, "ORD-001")).thenReturn(simNode);

        ResponseEntity<ApiResponse<JsonNode>> res = controller.getEligibility("ORD-001");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(res.getBody().getData().has("sku_eligibility"));
    }

    @Test
    @DisplayName("validateRejectReason accepts D9 codes and throws BusinessException for unknown codes")
    void validateRejectReason_ValidAndInvalid() {
        assertDoesNotThrow(() -> service.validateRejectReason("ITEM_AS_DESCRIBED"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.validateRejectReason("BAD_CODE"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("INVALID_REJECT_REASON", ex.getErrorCode());
    }
}

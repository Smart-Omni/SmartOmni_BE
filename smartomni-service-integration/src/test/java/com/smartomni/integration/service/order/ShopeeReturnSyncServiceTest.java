package com.smartomni.integration.service.order;

import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnItem;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ShopeeReturnDetail;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopeeReturnSyncServiceTest {

    @Mock
    private MarketplaceConnectionRepository connectionRepository;

    @Mock
    private ShopeeReturnsClient returnsClient;

    @Mock
    private OrderServiceClient orderServiceClient;

    private ShopeeReturnSyncService returnSyncService;

    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        returnSyncService = new ShopeeReturnSyncService(connectionRepository, returnsClient, orderServiceClient);

        connection = new MarketplaceConnection();
        connection.setId(10L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setShopId("727720655");
    }

    @Test
    @DisplayName("syncReturn: status ACCEPTED vật lý -> AWAITING_INSPECTION và restockRequired = false")
    void syncReturn_statusAcceptedPhysical_dispatchesAwaitingInspectionWithRestockFalse() {
        when(connectionRepository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.of(connection));

        ShopeeReturnDetail detail = new ShopeeReturnDetail(
                "RET_SP_1001",
                "261019TEST01",
                "ACCEPTED",
                BigDecimal.valueOf(150000),
                "VND",
                "ITEM_DAMAGED",
                "Hàng bị rách bao bì",
                "buyer_test",
                List.of(new ReturnItem(1001L, 2001L, "Áo sơ mi trắng", 1, BigDecimal.valueOf(150000), BigDecimal.valueOf(150000))),
                List.of("https://shopee.vn/proof1.jpg"),
                List.of(),
                BigDecimal.valueOf(25000),
                "Đồng ý trả hàng hoàn tiền"
        );

        when(returnsClient.getReturnDetail(10L, "RET_SP_1001")).thenReturn(detail);

        ProcessReturnRequest request = returnSyncService.syncReturn(1L, "727720655", "261019TEST01", "RET_SP_1001");

        assertNotNull(request);
        assertEquals("SHOPEE", request.getPlatform());
        assertEquals("261019TEST01", request.getOrderId());
        assertEquals("RET_SP_1001", request.getReturnId());
        assertEquals(10L, request.getConnectionId());
        assertEquals("PHYSICAL_RETURN", request.getReturnType());
        assertEquals(ProcessReturnRequest.ReturnAction.AWAITING_INSPECTION, request.getReturnAction());
        assertFalse(request.getRestockRequired());
        assertEquals(1, request.getItems().size());
        assertEquals("1001", request.getItems().get(0).getExternalItemId());
        assertEquals(1, request.getItems().get(0).getQuantity());
        assertEquals(List.of("https://shopee.vn/proof1.jpg"), request.getEvidenceUrls());

        ArgumentCaptor<ProcessReturnRequest> captor = ArgumentCaptor.forClass(ProcessReturnRequest.class);
        verify(orderServiceClient).processReturn(captor.capture());
        assertEquals("RET_SP_1001", captor.getValue().getReturnId());
    }

    @Test
    @DisplayName("syncReturn: status ACCEPTED và lý do NOT_RECEIVED -> REFUND_ONLY và restockRequired = false")
    void syncReturn_statusAcceptedNotReceived_dispatchesRefundOnlyWithRestockFalse() {
        when(connectionRepository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.of(connection));

        ShopeeReturnDetail detail = new ShopeeReturnDetail(
                "RET_SP_1002",
                "261019TEST02",
                "ACCEPTED",
                BigDecimal.valueOf(200000),
                "VND",
                "NOT_RECEIVED",
                "Chưa nhận được hàng",
                "buyer_test2",
                List.of(),
                List.of(),
                List.of(),
                BigDecimal.ZERO,
                "Hoàn tiền không hoàn hàng"
        );

        when(returnsClient.getReturnDetail(10L, "RET_SP_1002")).thenReturn(detail);

        ProcessReturnRequest request = returnSyncService.syncReturn(1L, "727720655", "261019TEST02", "RET_SP_1002");

        assertNotNull(request);
        assertEquals(ProcessReturnRequest.ReturnAction.REFUND_ONLY, request.getReturnAction());
        assertFalse(request.getRestockRequired());
        verify(orderServiceClient).processReturn(any(ProcessReturnRequest.class));
    }

    @Test
    @DisplayName("syncReturn: status SELLER_DISPUTE -> DISPUTE_CLAIM và restockRequired = false")
    void syncReturn_statusSellerDispute_dispatchesDisputeClaimWithRestockFalse() {
        when(connectionRepository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.of(connection));

        ShopeeReturnDetail detail = new ShopeeReturnDetail(
                "RET_SP_1003",
                "261019TEST03",
                "SELLER_DISPUTE",
                BigDecimal.valueOf(300000),
                "VND",
                "ITEM_DAMAGED",
                "Người bán khiếu nại bằng chứng người mua giả mạo",
                "buyer_test3",
                List.of(),
                List.of(),
                List.of(),
                BigDecimal.ZERO,
                "Đang chờ Shopee phán quyết"
        );

        when(returnsClient.getReturnDetail(10L, "RET_SP_1003")).thenReturn(detail);

        ProcessReturnRequest request = returnSyncService.syncReturn(1L, "727720655", "261019TEST03", "RET_SP_1003");

        assertNotNull(request);
        assertEquals(ProcessReturnRequest.ReturnAction.DISPUTE_CLAIM, request.getReturnAction());
        assertFalse(request.getRestockRequired());
        verify(orderServiceClient).processReturn(any(ProcessReturnRequest.class));
    }

    @Test
    @DisplayName("syncReturn: không tìm thấy connection ném BusinessException 404")
    void syncReturn_connectionNotFound_throwsBusinessException() {
        when(connectionRepository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.empty());
        when(connectionRepository.findFirstByShopId("727720655"))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () ->
                returnSyncService.syncReturn(1L, "727720655", "261019TEST01", "RET_SP_9999"));

        verifyNoInteractions(returnsClient, orderServiceClient);
    }
}

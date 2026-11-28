package com.smartomni.integration.client.order;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.OrderFinanceSnapshotRequest;
import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.common.dto.UpdateOrderTrackingRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign Client gọi sang smartomni-service-order để cập nhật thông tin vận đơn, tracking và đổi trả (Giai đoạn 4).
 */
@FeignClient(name = "smartomni-service-order", url = "${smartomni.services.order-url:http://localhost:8085}",
        configuration = OrderServiceClientAuth.class)
public interface OrderServiceClient {

    /** POST, not PATCH: Feign's default HttpURLConnection client cannot send PATCH ("Invalid HTTP method"). */
    @PostMapping("/api/v1/orders/tracking")
    ApiResponse<Void> updateTracking(@RequestBody UpdateOrderTrackingRequest request);

    @PostMapping("/api/v1/orders/returns")
    ApiResponse<Void> processReturn(@RequestBody ProcessReturnRequest request);

    @PostMapping("/api/v1/orders/finance-snapshots")
    ApiResponse<Void> upsertFinanceSnapshot(@RequestBody OrderFinanceSnapshotRequest request);
}

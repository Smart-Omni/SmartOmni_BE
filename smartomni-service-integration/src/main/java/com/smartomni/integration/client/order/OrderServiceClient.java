package com.smartomni.integration.client.order;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.UpdateOrderTrackingRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign Client gọi sang smartomni-service-order để cập nhật thông tin vận đơn và tracking (Giai đoạn 4).
 */
@FeignClient(name = "smartomni-service-order", url = "${smartomni.services.order-url:http://localhost:8085}")
public interface OrderServiceClient {

    @PatchMapping("/api/orders/tracking")
    ApiResponse<Void> updateTracking(@RequestBody UpdateOrderTrackingRequest request);
}

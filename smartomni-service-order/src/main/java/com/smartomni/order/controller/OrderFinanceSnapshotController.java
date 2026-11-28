package com.smartomni.order.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.OrderFinanceSnapshotRequest;
import com.smartomni.order.service.OrderFinanceSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal/integration endpoint to persist normalized marketplace finance snapshots.
 */
@RestController
@RequestMapping("/api/v1/orders/finance-snapshots")
@RequiredArgsConstructor
public class OrderFinanceSnapshotController {

    private final OrderFinanceSnapshotService snapshotService;

    @PostMapping
    public ApiResponse<Void> upsert(@RequestBody OrderFinanceSnapshotRequest request) {
        snapshotService.upsert(request);
        return ApiResponse.success(null);
    }
}

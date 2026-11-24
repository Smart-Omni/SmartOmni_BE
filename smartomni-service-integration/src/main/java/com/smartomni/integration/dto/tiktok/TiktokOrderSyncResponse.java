package com.smartomni.integration.dto.tiktok;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sync summary returned by {@code POST /api/v1/tiktok/orders/sync} (D2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TiktokOrderSyncResponse {

    private int totalFetched;
    /** Orders published to {@code order.incoming}; order-service creates or updates them asynchronously. */
    private int enqueuedCount;
    /** Orders that could not be published. */
    private int failedCount;
    /** Always 0 since the sync goes through the order queue; kept for API compatibility. */
    private int createdCount;
    /** Always 0 since the sync goes through the order queue; kept for API compatibility. */
    private int updatedCount;
    private List<TiktokOrderDetailResponse> orders;
}

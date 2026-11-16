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
    private int createdCount;
    private int updatedCount;
    private List<TiktokOrderDetailResponse> orders;
}

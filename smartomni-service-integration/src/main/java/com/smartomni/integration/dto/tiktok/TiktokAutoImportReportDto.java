package com.smartomni.integration.dto.tiktok;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary report for WF-01 TikTok Shop catalog auto-import.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TiktokAutoImportReportDto {

    private String shopId;
    private int totalProducts;
    private int totalSkus;
    private int importedCount;
    private int linkedExistingCount;
    private int skippedCount;
    private String status;
}

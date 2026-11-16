package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.tiktok.TiktokAutoImportReportDto;
import com.smartomni.integration.dto.tiktok.TiktokCatalogItemDto;
import com.smartomni.integration.service.catalog.TiktokAutoImportService;
import com.smartomni.integration.service.catalog.TiktokCatalogService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Exposes the TikTok Shop catalog view enriched with SmartOmni internal SKU mappings (D8)
 * and manual trigger for WF-01 catalog auto-import.
 */
@RestController
@RequestMapping("/api/v1/tiktok/catalog")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'STAFF')")
public class TiktokCatalogViewController {

    private final TiktokCatalogService catalogService;
    private final TiktokAutoImportService autoImportService;

    public TiktokCatalogViewController(TiktokCatalogService catalogService) {
        this(catalogService, null);
    }

    @Autowired
    public TiktokCatalogViewController(TiktokCatalogService catalogService,
                                       TiktokAutoImportService autoImportService) {
        this.catalogService = catalogService;
        this.autoImportService = autoImportService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TiktokCatalogItemDto>>> listCatalog() {
        return ResponseEntity.ok(ApiResponse.success(catalogService.listCatalogForCurrentTenant()));
    }

    /**
     * Manual trigger for WF-01 TikTok Shop catalog auto-import.
     */
    @PostMapping("/auto-import")
    public ResponseEntity<ApiResponse<TiktokAutoImportReportDto>> autoImport(
            @RequestParam(required = false) String shopId,
            @RequestBody(required = false) TiktokAutoImportRequest request) {
        String resolvedShopId = (request != null && request.getShopId() != null && !request.getShopId().isBlank())
                ? request.getShopId().trim()
                : (shopId != null && !shopId.isBlank() ? shopId.trim() : null);

        TiktokAutoImportReportDto report = autoImportService.autoImportForCurrentTenant(resolvedShopId);
        return ResponseEntity.ok(ApiResponse.success("Auto-import completed", report));
    }

    @Data
    public static class TiktokAutoImportRequest {
        private String shopId;
    }
}

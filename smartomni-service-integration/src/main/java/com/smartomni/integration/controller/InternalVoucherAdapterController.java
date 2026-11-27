package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.InternalIssueVoucherRequest;
import com.smartomni.integration.dto.InternalIssueVoucherResponse;
import com.smartomni.integration.dto.tiktok.TiktokVoucherBatchDto;
import com.smartomni.integration.service.MarketplaceVoucherAdapterService;
import com.smartomni.integration.service.tiktok.TiktokVoucherBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/integrations/vouchers")
@RequiredArgsConstructor
public class InternalVoucherAdapterController {

    private final MarketplaceVoucherAdapterService voucherAdapterService;
    private final TiktokVoucherBatchService batchService;

    @PostMapping("/issue")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<ApiResponse<InternalIssueVoucherResponse>> issueVoucher(
            @RequestBody InternalIssueVoucherRequest request) {
        return ResponseEntity.ok(ApiResponse.success(voucherAdapterService.issueVoucher(request)));
    }

    /** TikTok code batches of a connection of the JWT tenant, so the order service can check a tier's linked batch. */
    @GetMapping("/tiktok-batches")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<ApiResponse<List<TiktokVoucherBatchDto>>> tiktokBatches(@RequestParam Long connectionId) {
        return ResponseEntity.ok(ApiResponse.success(batchService.listForConnection(connectionId)));
    }
}

package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.InternalIssueVoucherRequest;
import com.smartomni.integration.dto.InternalIssueVoucherResponse;
import com.smartomni.integration.service.MarketplaceVoucherAdapterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/integrations/vouchers")
@RequiredArgsConstructor
public class InternalVoucherAdapterController {

    private final MarketplaceVoucherAdapterService voucherAdapterService;

    @PostMapping("/issue")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<ApiResponse<InternalIssueVoucherResponse>> issueVoucher(
            @RequestBody InternalIssueVoucherRequest request) {
        return ResponseEntity.ok(ApiResponse.success(voucherAdapterService.issueVoucher(request)));
    }
}

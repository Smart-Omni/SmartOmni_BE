package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.tiktok.CreateTiktokVoucherRequest;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.service.tiktok.TiktokVoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for issuing and listing TikTok Shop vouchers (WF-02 Part A).
 */
@RestController
@RequestMapping("/api/v1/tiktok/vouchers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER')")
public class TiktokVoucherController {

    private final TiktokVoucherService voucherService;

    @PostMapping
    public ResponseEntity<ApiResponse<TiktokVoucherDto>> createVoucher(@RequestBody CreateTiktokVoucherRequest request) {
        TiktokVoucherDto created = voucherService.createVoucher(request);
        return ResponseEntity.ok(ApiResponse.success("Voucher issued successfully", created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TiktokVoucherDto>>> listVouchers() {
        return ResponseEntity.ok(ApiResponse.success(voucherService.listVouchers()));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<List<TiktokVoucherDto>>> syncCoupons(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String title) {
        return ResponseEntity.ok(ApiResponse.success("Coupons synced from TikTok Simulator",
                voucherService.syncCouponsFromSimulator(title)));
    }
}

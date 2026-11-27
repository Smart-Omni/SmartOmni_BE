package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.tiktok.TiktokAftersaleCodesDto;
import com.smartomni.integration.dto.tiktok.TiktokAftersaleCodesDto.ReasonCodeItem;
import com.smartomni.integration.service.tiktok.TiktokAftersaleCodesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * D9 REST controller exposing TikTok Shop aftersale reason codes, reject reasons,
 * status reference, and order aftersale eligibility.
 */
@RestController
@RequestMapping("/api/v1/tiktok/aftersale")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER')")
public class TiktokAftersaleController {

    private final TiktokAftersaleCodesService aftersaleCodesService;

    @GetMapping("/reasons")
    public ResponseEntity<ApiResponse<TiktokAftersaleCodesDto>> getAllReasons() {
        return ResponseEntity.ok(ApiResponse.success(aftersaleCodesService.getAllCodes()));
    }

    @GetMapping("/reject-reasons")
    public ResponseEntity<ApiResponse<List<ReasonCodeItem>>> getRejectReasons(
            @RequestParam(required = false) String returnOrCancelId) {
        return ResponseEntity.ok(ApiResponse.success(aftersaleCodesService.getRejectReasons(returnOrCancelId)));
    }

    @GetMapping("/eligibility/{orderId}")
    public ResponseEntity<ApiResponse<JsonNode>> getEligibility(@PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.success(aftersaleCodesService.getAftersaleEligibility(orderId)));
    }
}

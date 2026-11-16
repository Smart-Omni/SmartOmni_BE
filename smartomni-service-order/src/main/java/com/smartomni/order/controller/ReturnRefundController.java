package com.smartomni.order.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.ReturnDetailDto;
import com.smartomni.common.dto.ReturnInspectionRequest;
import com.smartomni.order.dto.CreateReturnRequest;
import com.smartomni.order.entity.ReturnRefund;
import com.smartomni.order.service.ReturnRefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

/** UC-13: Quan ly hoan/huy don hang, kiem dinh va nhap kho (WF-05, DS-09). */
@RestController
@RequestMapping("/api/v1/returns")
@RequiredArgsConstructor
public class ReturnRefundController {

    private final ReturnRefundService returnRefundService;

    @GetMapping
    public ApiResponse<Page<ReturnDetailDto>> list(Pageable pageable) {
        return ApiResponse.success(returnRefundService.getReturns(pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReturnDetailDto> getDetail(@PathVariable Long id) {
        return ApiResponse.success(returnRefundService.getReturnDetail(id));
    }

    @PostMapping
    public ApiResponse<ReturnRefund> create(@Valid @RequestBody CreateReturnRequest request) {
        return ApiResponse.success("Da tao yeu cau hoan/huy", returnRefundService.createReturnRequest(request));
    }

    @PostMapping("/{id}/inspection")
    public ApiResponse<ReturnDetailDto> inspect(
            @PathVariable Long id,
            @Valid @RequestBody ReturnInspectionRequest request) {
        return ApiResponse.success("Da kiem dinh va xu ly hoan tra", returnRefundService.performInspection(id, request));
    }

    @PostMapping("/{refundId}/approve")
    public ApiResponse<ReturnRefund> approve(@PathVariable Long refundId) {
        return ApiResponse.success("Da duyet va cong tra ton kho", returnRefundService.approveAndRestock(refundId));
    }
}

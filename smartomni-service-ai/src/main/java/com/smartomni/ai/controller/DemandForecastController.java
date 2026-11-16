package com.smartomni.ai.controller;

import com.smartomni.ai.entity.DemandForecast;
import com.smartomni.ai.service.DemandForecastService;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** UC-14/UC-35: Du bao nhu cau hang hoa & canh bao ton kho. */
@RestController
@RequestMapping("/api/v1/ai/forecast")
@RequiredArgsConstructor
public class DemandForecastController {

    private final DemandForecastService demandForecastService;

    @PostMapping("/run")
    public ApiResponse<Void> runForecast() {
        demandForecastService.runForecastForTenant(TenantContext.getTenantId());
        return ApiResponse.success("Da chay du bao nhu cau", null);
    }

    @GetMapping("/{skuId}")
    public ApiResponse<List<DemandForecast>> getForecasts(
            @PathVariable Long skuId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "days", required = false) Integer days) {
        return ApiResponse.success(demandForecastService.getForecastsForSku(TenantContext.getTenantId(), skuId, from, days));
    }
}

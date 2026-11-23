package com.smartomni.integration.controller;

import com.smartomni.integration.service.tiktok.TiktokStockPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/jobs")
public class InternalTiktokStockController {
    private final TiktokStockPushService pushService;

    public record StockPush(long tenantId, long skuId, int quantity) { }

    @PostMapping("/push-tiktok-stock")
    public ResponseEntity<Void> push(@RequestBody StockPush request) {
        pushService.push(request.tenantId(), request.skuId(), request.quantity());
        return ResponseEntity.noContent().build();
    }
}

package com.smartomni.order.controller;

import com.smartomni.order.scheduler.OrderPollingScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/jobs")
@RequiredArgsConstructor
public class InternalOrderJobsController {
    private final OrderPollingScheduler scheduler;

    @PostMapping("/poll-orders")
    public ResponseEntity<Void> pollOrders() {
        scheduler.pollOrdersFromMarketplaces();
        return ResponseEntity.noContent().build();
    }
}

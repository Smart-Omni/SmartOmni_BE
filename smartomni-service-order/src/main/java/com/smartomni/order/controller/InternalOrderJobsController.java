package com.smartomni.order.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.order.dto.LoyaltyAwardResultDto;
import com.smartomni.order.scheduler.OrderPollingScheduler;
import com.smartomni.order.service.LoyaltyAwardService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/jobs")
public class InternalOrderJobsController {
    private final OrderPollingScheduler scheduler;
    private final LoyaltyAwardService loyaltyAwardService;

    public InternalOrderJobsController(OrderPollingScheduler scheduler) {
        this(scheduler, null);
    }

    @Autowired
    public InternalOrderJobsController(OrderPollingScheduler scheduler,
                                       @Autowired(required = false) LoyaltyAwardService loyaltyAwardService) {
        this.scheduler = scheduler;
        this.loyaltyAwardService = loyaltyAwardService;
    }

    @PostMapping("/poll-orders")
    public ResponseEntity<Void> pollOrders() {
        scheduler.pollOrdersFromMarketplaces();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/award-loyalty-points")
    public ResponseEntity<ApiResponse<LoyaltyAwardResultDto>> awardLoyaltyPoints() {
        LoyaltyAwardResultDto result = loyaltyAwardService != null
                ? loyaltyAwardService.awardEligiblePointsForAllTenants()
                : LoyaltyAwardResultDto.builder().build();
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}

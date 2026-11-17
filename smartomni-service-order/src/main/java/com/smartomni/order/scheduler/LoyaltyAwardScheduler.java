package com.smartomni.order.scheduler;

import com.smartomni.order.service.LoyaltyAwardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic scheduler for WF-02 15-day loyalty point awards.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoyaltyAwardScheduler {

    private final LoyaltyAwardService loyaltyAwardService;

    @Scheduled(
            initialDelayString = "${smartomni.loyalty.award-initial-delay-ms:3600000}",
            fixedDelayString = "${smartomni.loyalty.award-interval-ms:3600000}"
    )
    public void runPeriodicAward() {
        log.debug("Running periodic WF-02 loyalty point award job");
        loyaltyAwardService.awardEligiblePointsForAllTenants();
    }
}

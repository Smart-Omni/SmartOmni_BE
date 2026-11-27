package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.ClaimPointsRequest;
import com.smartomni.order.dto.loyalty.LoyaltyClaimDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Entry point of a shopper's loyalty claim. NOT transactional on purpose: when the order is missing or not completed
 * locally it asks TikTok Shop for the order (the "missing data" fallback), then waits briefly for the asynchronous
 * ingestion before retrying the transactional claim in {@link LoyaltyCustomerService}.
 *
 * <p>Claims by marketplace order id do not check who bought the order, so unknown ids are rate limited per customer.
 */
@Slf4j
@Service
public class LoyaltyClaimService {

    static final int MAX_UNKNOWN_ORDERS_PER_HOUR = 10;
    static final int SYNC_WAIT_ATTEMPTS = 6;
    static final Duration SYNC_WAIT_STEP = Duration.ofMillis(500);
    private static final Set<String> NOT_SYNCED = Set.of("ORDER_NOT_FOUND", "ORDER_NOT_COMPLETED");

    private final LoyaltyCustomerService customerService;
    private final TiktokOrderRefreshClient refreshClient;
    private final StringRedisTemplate redis;
    private final Sleeper sleeper;

    @Autowired
    public LoyaltyClaimService(LoyaltyCustomerService customerService, TiktokOrderRefreshClient refreshClient,
                               ObjectProvider<StringRedisTemplate> redis) {
        this(customerService, refreshClient, redis.getIfAvailable(), Sleeper.THREAD);
    }

    LoyaltyClaimService(LoyaltyCustomerService customerService, TiktokOrderRefreshClient refreshClient,
                        StringRedisTemplate redis, Sleeper sleeper) {
        this.customerService = customerService;
        this.refreshClient = refreshClient;
        this.redis = redis;
        this.sleeper = sleeper;
    }

    public LoyaltyClaimDto claim(Long customerId, Long tenantId, ClaimPointsRequest request) {
        if (request == null || !request.byPlatformOrderId()) {
            return customerService.claimOrderPoints(customerId, tenantId, request);
        }
        String key = attemptsKey(tenantId, customerId);
        if (unknownAttempts(key) >= MAX_UNKNOWN_ORDERS_PER_HOUR) {
            throw new BusinessException("Too many unknown order ids; try again later",
                    HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CLAIM_ATTEMPTS");
        }
        try {
            return customerService.claimOrderPoints(customerId, tenantId, request);
        } catch (BusinessException local) {
            if (!NOT_SYNCED.contains(local.getErrorCode()) || !mayAskTiktok(request)) {
                countUnknown(key, local);
                throw local;
            }
            TiktokOrderRefreshClient.Result tiktok = refreshClient.refresh(tenantId, request.getPlatformOrderId().trim());
            if (tiktok.outcome() != TiktokOrderRefreshClient.Outcome.FOUND) {
                countUnknown(key, local);
                throw local;
            }
            if (!tiktok.completed()) {
                throw new BusinessException("Only COMPLETED orders can be claimed for loyalty points (TikTok status: "
                        + tiktok.orderStatus() + ")", HttpStatus.BAD_REQUEST, "ORDER_NOT_COMPLETED");
            }
            return claimAfterSync(customerId, tenantId, request);
        }
    }

    private LoyaltyClaimDto claimAfterSync(Long customerId, Long tenantId, ClaimPointsRequest request) {
        for (int attempt = 0; attempt < SYNC_WAIT_ATTEMPTS; attempt++) {
            sleeper.sleep(SYNC_WAIT_STEP);
            try {
                return customerService.claimOrderPoints(customerId, tenantId, request);
            } catch (BusinessException e) {
                if (!NOT_SYNCED.contains(e.getErrorCode())) {
                    throw e;
                }
            }
        }
        throw new BusinessException("The order is being synced from TikTok Shop; try again in a few minutes",
                HttpStatus.CONFLICT, "ORDER_SYNCING");
    }

    private static boolean mayAskTiktok(ClaimPointsRequest request) {
        String platform = LoyaltyCustomerService.normalizePlatform(request.getPlatform());
        return platform == null || "tiktok_shop".equals(platform);
    }

    private static String attemptsKey(Long tenantId, Long customerId) {
        return "loyalty:claim-unknown:" + tenantId + ":" + customerId;
    }

    private long unknownAttempts(String key) {
        if (redis == null) {
            return 0;
        }
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("Could not read loyalty claim attempts: {}", e.getMessage());
            return 0;
        }
    }

    private void countUnknown(String key, BusinessException e) {
        if (redis == null || !"ORDER_NOT_FOUND".equals(e.getErrorCode())) {
            return;
        }
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofHours(1));
            }
        } catch (Exception ex) {
            log.warn("Could not record loyalty claim attempt: {}", ex.getMessage());
        }
    }

    @FunctionalInterface
    interface Sleeper {
        Sleeper THREAD = d -> {
            try {
                Thread.sleep(d.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("Claim interrupted", HttpStatus.SERVICE_UNAVAILABLE, "CLAIM_INTERRUPTED");
            }
        };

        void sleep(Duration duration);
    }
}

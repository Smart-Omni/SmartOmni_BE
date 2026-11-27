package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.ClaimPointsRequest;
import com.smartomni.order.dto.loyalty.LoyaltyClaimDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoyaltyClaimServiceTest {

    private final LoyaltyCustomerService customers = mock(LoyaltyCustomerService.class);
    private final TiktokOrderRefreshClient tiktok = mock(TiktokOrderRefreshClient.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final List<Duration> sleeps = new ArrayList<>();
    private LoyaltyClaimService service;

    private final ClaimPointsRequest byMarketplaceId = ClaimPointsRequest.builder().platformOrderId(" 5760028171077135663 ").build();
    private final LoyaltyClaimDto claimed = LoyaltyClaimDto.builder().id(1L).pointsAwarded(184L).build();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        service = new LoyaltyClaimService(customers, tiktok, redis, sleeps::add);
    }

    private static BusinessException error(String code) {
        return new BusinessException(code, HttpStatus.BAD_REQUEST, code);
    }

    @Test
    void legacyInternalIdClaimSkipsTiktokAndRateLimit() {
        ClaimPointsRequest legacy = new ClaimPointsRequest(100L, BigDecimal.valueOf(250000));
        when(customers.claimOrderPoints(10L, 1L, legacy)).thenReturn(claimed);

        assertThat(service.claim(10L, 1L, legacy)).isSameAs(claimed);
        verify(tiktok, never()).refresh(anyLong(), anyString());
        verify(redis, never()).opsForValue();
    }

    @Test
    void syncedOrderIsClaimedWithoutAskingTiktok() {
        when(customers.claimOrderPoints(10L, 1L, byMarketplaceId)).thenReturn(claimed);

        assertThat(service.claim(10L, 1L, byMarketplaceId)).isSameAs(claimed);
        verify(tiktok, never()).refresh(anyLong(), anyString());
    }

    @Test
    void unsyncedCompletedTiktokOrderIsClaimedOnceIngestionLands() {
        when(customers.claimOrderPoints(10L, 1L, byMarketplaceId))
                .thenThrow(error("ORDER_NOT_FOUND"))
                .thenThrow(error("ORDER_NOT_FOUND"))
                .thenReturn(claimed);
        when(tiktok.refresh(1L, "5760028171077135663"))
                .thenReturn(new TiktokOrderRefreshClient.Result(TiktokOrderRefreshClient.Outcome.FOUND, "COMPLETED"));

        assertThat(service.claim(10L, 1L, byMarketplaceId)).isSameAs(claimed);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void tiktokOrderThatIsNotCompletedIsRejectedWithItsStatus() {
        when(customers.claimOrderPoints(10L, 1L, byMarketplaceId)).thenThrow(error("ORDER_NOT_FOUND"));
        when(tiktok.refresh(anyLong(), anyString()))
                .thenReturn(new TiktokOrderRefreshClient.Result(TiktokOrderRefreshClient.Outcome.FOUND, "IN_TRANSIT"));

        assertThatThrownBy(() -> service.claim(10L, 1L, byMarketplaceId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo("ORDER_NOT_COMPLETED");
                    assertThat(e.getMessage()).contains("IN_TRANSIT");
                });
        assertThat(sleeps).isEmpty();
    }

    @Test
    void completedOnTiktokButStillNotLocalAfterWaitingIsSyncing() {
        when(customers.claimOrderPoints(10L, 1L, byMarketplaceId)).thenThrow(error("ORDER_NOT_FOUND"));
        when(tiktok.refresh(anyLong(), anyString()))
                .thenReturn(new TiktokOrderRefreshClient.Result(TiktokOrderRefreshClient.Outcome.FOUND, "COMPLETED"));

        assertThatThrownBy(() -> service.claim(10L, 1L, byMarketplaceId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("ORDER_SYNCING"));
        assertThat(sleeps).hasSize(LoyaltyClaimService.SYNC_WAIT_ATTEMPTS);
    }

    @Test
    void unknownEverywhereKeepsNotFoundAndCountsTheAttempt() {
        when(customers.claimOrderPoints(10L, 1L, byMarketplaceId)).thenThrow(error("ORDER_NOT_FOUND"));
        when(tiktok.refresh(anyLong(), anyString()))
                .thenReturn(new TiktokOrderRefreshClient.Result(TiktokOrderRefreshClient.Outcome.NOT_FOUND, null));
        when(values.increment(anyString())).thenReturn(1L);

        assertThatThrownBy(() -> service.claim(10L, 1L, byMarketplaceId))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("ORDER_NOT_FOUND"));
        verify(values).increment("loyalty:claim-unknown:1:10");
        verify(redis).expire(eq("loyalty:claim-unknown:1:10"), any(Duration.class));
    }

    @Test
    void otherRejectionsAreNotRetriedOrCounted() {
        when(customers.claimOrderPoints(10L, 1L, byMarketplaceId)).thenThrow(error("ORDER_ALREADY_CLAIMED"));

        assertThatThrownBy(() -> service.claim(10L, 1L, byMarketplaceId))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("ORDER_ALREADY_CLAIMED"));
        verify(tiktok, never()).refresh(anyLong(), anyString());
        verify(values, never()).increment(anyString());
    }

    @Test
    void shopeeClaimsNeverAskTiktok() {
        ClaimPointsRequest shopee = ClaimPointsRequest.builder().platformOrderId("2611SHOPEE").platform("shopee").build();
        when(customers.claimOrderPoints(10L, 1L, shopee)).thenThrow(error("ORDER_NOT_FOUND"));

        assertThatThrownBy(() -> service.claim(10L, 1L, shopee)).isInstanceOf(BusinessException.class);
        verify(tiktok, never()).refresh(anyLong(), anyString());
    }

    @Test
    void tooManyUnknownOrderIdsAreBlocked() {
        when(values.get("loyalty:claim-unknown:1:10")).thenReturn(String.valueOf(LoyaltyClaimService.MAX_UNKNOWN_ORDERS_PER_HOUR));

        assertThatThrownBy(() -> service.claim(10L, 1L, byMarketplaceId))
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo("TOO_MANY_CLAIM_ATTEMPTS");
                    assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
        verify(customers, times(0)).claimOrderPoints(any(), any(), any());
    }
}

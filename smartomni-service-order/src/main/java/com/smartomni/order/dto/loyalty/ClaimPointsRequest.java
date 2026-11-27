package com.smartomni.order.dto.loyalty;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A loyalty claim. Either the marketplace order id the shopper sees on Shopee/TikTok ({@code platformOrderId},
 * optionally with {@code platform}), or the legacy pair internal {@code orderId} + matching {@code totalAmount}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimPointsRequest {
    @JsonAlias("order_id")
    private Long orderId;

    @JsonAlias({"orderAmount", "total_amount", "order_amount"})
    private BigDecimal totalAmount;

    @JsonAlias({"platform_order_id", "orderSn", "order_sn"})
    private String platformOrderId;

    /** {@code shopee} or {@code tiktok_shop}; only needed when the same id exists on both marketplaces. */
    private String platform;

    public ClaimPointsRequest(Long orderId, BigDecimal totalAmount) {
        this.orderId = orderId;
        this.totalAmount = totalAmount;
    }

    public boolean byPlatformOrderId() {
        return orderId == null && platformOrderId != null && !platformOrderId.isBlank();
    }
}

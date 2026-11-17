package com.smartomni.order.dto.loyalty;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimPointsRequest {
    @NotNull(message = "orderId is required")
    @JsonAlias("order_id")
    private Long orderId;

    @NotNull(message = "totalAmount is required")
    @Positive(message = "totalAmount must be greater than 0")
    @JsonAlias({"orderAmount", "total_amount", "order_amount"})
    private BigDecimal totalAmount;
}

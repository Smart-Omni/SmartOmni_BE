package com.smartomni.order.dto.loyalty;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
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

    @JsonAlias({"orderAmount", "total_amount", "order_amount"})
    private BigDecimal totalAmount;
}

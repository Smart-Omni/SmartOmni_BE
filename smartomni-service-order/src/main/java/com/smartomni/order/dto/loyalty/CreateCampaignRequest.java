package com.smartomni.order.dto.loyalty;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCampaignRequest {
    @NotNull(message = "connectionId is required")
    private Long connectionId;

    @NotBlank(message = "name is required")
    private String name;

    @NotNull(message = "discountPercent is required")
    @DecimalMin(value = "0.01", message = "discountPercent must be > 0")
    @DecimalMax(value = "100.00", message = "discountPercent must be <= 100")
    private BigDecimal discountPercent;

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be >= 1")
    private Integer quantity;

    @NotNull(message = "requiredPoints is required")
    @Min(value = 1, message = "requiredPoints must be >= 1")
    private Long requiredPoints;

    private String provisioningMode;
    private String externalCouponId;
    private String status;

    @NotNull(message = "startsAt is required")
    private Instant startsAt;

    @NotNull(message = "endsAt is required")
    private Instant endsAt;
}

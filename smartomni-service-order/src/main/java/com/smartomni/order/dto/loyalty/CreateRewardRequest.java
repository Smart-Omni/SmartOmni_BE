package com.smartomni.order.dto.loyalty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRewardRequest {
    @NotNull(message = "campaignId is required")
    private Long campaignId;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;
}

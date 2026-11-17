package com.smartomni.order.dto.loyalty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationIssueVoucherResponse {
    private String outcome;
    private String externalVoucherId;
    private String code;
    private String failureReason;
}

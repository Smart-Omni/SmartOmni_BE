package com.smartomni.integration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalIssueVoucherResponse {
    /** ISSUED, FAILED, or PENDING (ambiguous/transient timeout). */
    private String outcome;
    private String externalVoucherId;
    private String code;
    private String failureReason;
}

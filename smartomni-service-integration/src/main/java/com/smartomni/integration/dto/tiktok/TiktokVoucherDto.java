package com.smartomni.integration.dto.tiktok;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO representing an issued TikTok Shop voucher stored in SmartOmni DB (WF-02 Part A).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TiktokVoucherDto {

    private Long id;
    private Long tenantId;
    private Long connectionId;
    private String shopId;
    private String tiktokVoucherId;
    private String voucherCode;
    private String title;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal minOrderAmount;
    private Integer usageLimit;
    private Integer redeemedCount;
    private Instant startTime;
    private Instant endTime;
    private String status;
    private Instant createdAt;
}

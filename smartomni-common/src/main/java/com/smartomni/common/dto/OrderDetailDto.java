package com.smartomni.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailDto {
    private Long id;
    private Long tenantId;
    private String platform;
    private Long connectionId;
    private String platformOrderId;
    private String status;
    private Instant slaDeadline;
    private String shippingMethod;
    private String trackingNumber;
    private Instant courierScannedAt;
    private String customerName;
    private String customerPhone;
    private String shippingAddress;
    private BigDecimal totalAmount;
    private String source;
    private String returnReason;
    private String rejectReason;
    private String restockStatus;
    private Instant createdAt;
    private List<OrderItemDto> items;
}

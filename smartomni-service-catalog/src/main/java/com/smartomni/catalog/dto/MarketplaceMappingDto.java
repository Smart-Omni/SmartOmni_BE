package com.smartomni.catalog.dto;

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
public class MarketplaceMappingDto {
    private Long id;
    private String platform;
    private String platformItemId;
    private String externalSkuId;
    private String sellerSku;
    private BigDecimal platformPrice;
    private String currency;
    private String externalStatus;
    private Instant lastSyncedAt;
    private String productUrl;
}

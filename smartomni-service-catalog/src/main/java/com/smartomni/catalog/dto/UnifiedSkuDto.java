package com.smartomni.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedSkuDto {
    private Long id;
    private String skuCode;
    private String variantName;
    private BigDecimal basePrice;
    private Integer availableQuantity;
    private MarketplaceMappingDto shopeeMapping;
    private MarketplaceMappingDto tiktokMapping;
    private SuggestedMappingDto suggestedMapping;
}

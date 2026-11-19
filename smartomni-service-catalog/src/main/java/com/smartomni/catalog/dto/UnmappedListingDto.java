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
public class UnmappedListingDto {
    private String itemId;
    private String externalSkuId;
    private String sellerSku;
    private String name;
    private String variant;
    private BigDecimal price;
    private String image;
    private String productUrl;
}

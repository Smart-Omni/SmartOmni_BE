package com.smartomni.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItemDto {
    private Long skuId;
    private String skuCode;
    private String productName;
    private Integer quantityOnHand;
    private Integer reservedQuantity;
    private Integer availableQuantity;
}

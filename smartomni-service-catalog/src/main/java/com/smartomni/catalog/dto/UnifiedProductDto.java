package com.smartomni.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedProductDto {
    private Long id;
    private String name;
    private String description;
    private String status;
    private String category;
    private String imageUrl;
    private String shopeeProductUrl;
    private String tiktokProductUrl;
    private List<UnifiedSkuDto> skus;
}

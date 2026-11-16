package com.smartomni.integration.dto.shopee;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateShopeeSettingsRequest {
    @JsonProperty("auto_import_products")
    private Boolean autoImportProducts;
}

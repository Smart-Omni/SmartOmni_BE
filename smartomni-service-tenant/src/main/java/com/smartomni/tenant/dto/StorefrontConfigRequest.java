package com.smartomni.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorefrontConfigRequest {

    @NotBlank(message = "storeName không được để trống")
    private String storeName;

    private String logoUrl;
    private String bannerUrl;
    private String primaryColor;
    private String supportPhone;
    private String address;
}

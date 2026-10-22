package com.smartomni.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Thong tin Storefront cong khai cua Tenant phuc vu trang mua hang cua khach (Logo, Ten shop, Mau chu dao).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicTenantStorefrontResponse {

    private String subdomain;
    private String storeName;
    private String logoUrl;
    private String bannerUrl;
    private String primaryColor;
    private String supportPhone;
    private String address;
    private String status;
}

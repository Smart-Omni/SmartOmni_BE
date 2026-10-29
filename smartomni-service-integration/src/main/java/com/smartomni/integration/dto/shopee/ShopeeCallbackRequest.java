package com.smartomni.integration.dto.shopee;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * What the SmartOmni frontend callback page forwards from the redirect query string.
 * Carries no tenant ID on purpose: tenant comes from the verified JWT.
 */
@Getter
@Setter
public class ShopeeCallbackRequest {
    private String code;

    @JsonProperty("shop_id")
    private String shopId;

    private String state;
    private String error;

    @JsonProperty("error_description")
    private String errorDescription;

    @Override
    public String toString() {
        return "ShopeeCallbackRequest[redacted]";
    }
}

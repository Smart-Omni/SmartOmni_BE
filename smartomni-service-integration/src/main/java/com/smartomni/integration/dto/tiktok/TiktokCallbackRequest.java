package com.smartomni.integration.dto.tiktok;

import lombok.Getter;
import lombok.Setter;

/**
 * What the SmartOmni frontend callback page forwards from the redirect query string. Carries no tenant
 * ID on purpose: the tenant comes from the verified JWT and must match the one bound to the state.
 * Parameter names {@code code}, {@code state}, {@code error}, {@code error_description} are UNVERIFIED.
 */
@Getter
@Setter
public class TiktokCallbackRequest {
    private String code;
    private String state;
    private String error;
    private String errorDescription;

    @Override
    public String toString() {
        return "TiktokCallbackRequest[redacted]";
    }
}

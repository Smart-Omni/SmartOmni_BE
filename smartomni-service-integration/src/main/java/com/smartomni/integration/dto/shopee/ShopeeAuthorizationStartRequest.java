package com.smartomni.integration.dto.shopee;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopeeAuthorizationStartRequest {
    @JsonProperty("redirect_uri")
    private String redirectUri;
}

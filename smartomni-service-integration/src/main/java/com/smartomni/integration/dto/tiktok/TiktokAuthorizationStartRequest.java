package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TiktokAuthorizationStartRequest(@JsonProperty("redirect_uri") String redirectUri) {
}

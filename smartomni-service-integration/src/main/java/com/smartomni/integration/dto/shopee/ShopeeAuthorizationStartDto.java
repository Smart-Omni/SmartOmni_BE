package com.smartomni.integration.dto.shopee;

import java.time.Instant;

/** Response of "start connection": where to send the seller, and when the single-use state expires. */
public record ShopeeAuthorizationStartDto(String authorizationUrl, Instant expiresAt) {
}

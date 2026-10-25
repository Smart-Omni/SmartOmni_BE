package com.smartomni.integration.dto.tiktok;

import java.time.Instant;

/** Response of "start connection": where to send the seller, and when the single-use state expires. */
public record TiktokAuthorizationStartDto(String authorizationUrl, Instant expiresAt) {
}

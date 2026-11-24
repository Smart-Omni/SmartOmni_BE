package com.smartomni.integration.exception;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a failed TikTok call into an explicit HTTP error instead of the generic 500 of
 * {@code GlobalExceptionHandler}. Only {@link TiktokApiException} is handled here; every other exception still
 * reaches the shared handler. Ordered first because Spring stops at the first advice that has any matching
 * handler, and the shared handler matches {@code Exception}.
 *
 * <table>
 *   <tr><th>TikTok failure</th><th>Response</th></tr>
 *   <tr><td>network error, timeout or HTTP 5xx</td><td>503 {@code TIKTOK_UNAVAILABLE}</td></tr>
 *   <tr><td>HTTP 403 for a missing scope</td><td>409 {@code TIKTOK_SCOPE_MISSING}</td></tr>
 *   <tr><td>other HTTP 401 / 403</td><td>409 {@code TIKTOK_RECONNECT_REQUIRED}</td></tr>
 *   <tr><td>HTTP 404</td><td>502 {@code TIKTOK_NOT_FOUND}</td></tr>
 *   <tr><td>any other rejection (4xx, envelope code other than 0)</td><td>422 {@code TIKTOK_REQUEST_REJECTED}</td></tr>
 * </table>
 *
 * Messages carry {@link TiktokApiException#summary()} for 422 only and never the platform payload.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TiktokApiExceptionHandler {

    public static final String UNAVAILABLE = "TIKTOK_UNAVAILABLE";
    public static final String RECONNECT_REQUIRED = "TIKTOK_RECONNECT_REQUIRED";
    public static final String NOT_FOUND = "TIKTOK_NOT_FOUND";
    public static final String REQUEST_REJECTED = "TIKTOK_REQUEST_REJECTED";
    public static final String SCOPE_MISSING = "TIKTOK_SCOPE_MISSING";

    @ExceptionHandler(TiktokApiException.class)
    public ResponseEntity<ApiResponse<Object>> handle(TiktokApiException ex) {
        log.warn("TikTok call failed: {} ({})", ex.getMessage(), ex.summary());
        Mapped mapped = map(ex);
        return ResponseEntity.status(mapped.status()).body(ApiResponse.error(mapped.message(), mapped.code()));
    }

    /** The HTTP status, error code and message SmartOmni returns for a TikTok failure. */
    public static Mapped map(TiktokApiException ex) {
        if (ex.isTransientFailure()) {
            return new Mapped(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE,
                    "TikTok is temporarily unavailable. Please try again later.");
        }
        int status = ex.getHttpStatus();
        if (status == 403 && isScopeFailure(ex)) {
            return new Mapped(HttpStatus.CONFLICT, SCOPE_MISSING,
                    "The TikTok authorization does not grant the permission this action needs. "
                            + "Reconnect the TikTok shop and approve all requested permissions.");
        }
        if (status == 401 || status == 403) {
            return new Mapped(HttpStatus.CONFLICT, RECONNECT_REQUIRED,
                    "TikTok refused the shop authorization. Reconnect the TikTok shop.");
        }
        if (status == 404) {
            return new Mapped(HttpStatus.BAD_GATEWAY, NOT_FOUND, "TikTok could not find the requested resource.");
        }
        return new Mapped(HttpStatus.UNPROCESSABLE_ENTITY, REQUEST_REJECTED,
                "TikTok rejected the request: " + ex.getMessage() + " (" + ex.summary() + ")");
    }

    /** TikTok's 403 for a scope the token lacks ("missing_scope": "the authorization does not grant ..."). */
    static boolean isScopeFailure(TiktokApiException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("scope") || message.contains("does not grant");
    }

    public record Mapped(HttpStatus status, String code, String message) {
    }
}

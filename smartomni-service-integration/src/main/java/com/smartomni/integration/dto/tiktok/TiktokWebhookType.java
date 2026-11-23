package com.smartomni.integration.dto.tiktok;

import java.util.Arrays;
import java.util.Optional;

/**
 * The numeric {@code type} of a TikTok Shop webhook and its topic. This enum is the only place where the numbers
 * appear: route by constant, never compare {@code payload.getType()} with a literal.
 *
 * <p>Values match {@code WEBHOOK_TYPE} in the simulator ({@code backend/src/webhooks/webhook-emitter.service.ts});
 * both sides are {@code unverified} against production TikTok. Topic names are the {@code event_type} strings of
 * {@code PUT /event/202309/webhooks}.
 */
public enum TiktokWebhookType {
    ORDER_STATUS_CHANGE(1),
    RECIPIENT_ADDRESS_UPDATE(2),
    PACKAGE_UPDATE(3),
    REVERSE_STATUS_UPDATE(4),
    PRODUCT_STATUS_CHANGE(5),
    SELLER_DEAUTHORIZATION(6),
    UPCOMING_AUTHORIZATION_EXPIRATION(7),
    PRODUCT_AUDIT_STATUS_CHANGE(8),
    CANCELLATION_STATUS_CHANGE(11),
    RETURN_STATUS_CHANGE(12),
    NEW_CONVERSATION(13),
    NEW_MESSAGE(14),
    DISPUTE_STATUS_CHANGE(15),
    INVENTORY_CHANGED_BY_SHOP(71);

    private final int code;

    TiktokWebhookType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** Topic name used by the Events API ({@code event_type}). */
    public String topic() {
        return name();
    }

    public static Optional<TiktokWebhookType> fromCode(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.code == code).findFirst();
    }

    /** Event type stored in {@code webhook_events_log}: the topic, or {@code TIKTOK_EVENT_TYPE_<n>} when unknown. */
    public static String logName(Integer code) {
        if (code == null) {
            return "UNKNOWN";
        }
        return fromCode(code).map(TiktokWebhookType::topic).orElse("TIKTOK_EVENT_TYPE_" + code);
    }
}

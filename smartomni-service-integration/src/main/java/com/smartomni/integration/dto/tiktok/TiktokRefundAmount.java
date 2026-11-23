package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * {@code refund_amount} of a TikTok return, cancellation or their line items. TikTok sends an object whose amounts
 * are strings: {@code {"currency":"VND","refund_subtotal":"150000","refund_shipping_fee":"0","refund_tax":"0",
 * "refund_total":"150000"}}. A bare number or string (older payloads) is read as {@code refund_total}.
 */
@JsonDeserialize(using = TiktokRefundAmount.Deserializer.class)
public record TiktokRefundAmount(
        @JsonProperty("currency") String currency,
        @JsonProperty("refund_subtotal") String refundSubtotal,
        @JsonProperty("refund_shipping_fee") String refundShippingFee,
        @JsonProperty("refund_tax") String refundTax,
        @JsonProperty("refund_total") String refundTotal) {

    /** {@code refund_total} as a number, or {@code null} when absent or not numeric. */
    @JsonIgnore
    public BigDecimal total() {
        return parse(refundTotal);
    }

    @JsonIgnore
    public BigDecimal subtotal() {
        return parse(refundSubtotal);
    }

    @JsonIgnore
    public BigDecimal shippingFee() {
        return parse(refundShippingFee);
    }

    @JsonIgnore
    public BigDecimal tax() {
        return parse(refundTax);
    }

    /** Null-safe {@link #total()} of an optional amount. */
    public static BigDecimal totalOf(TiktokRefundAmount amount) {
        return amount == null ? null : amount.total();
    }

    static BigDecimal parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Reads the object form, or a scalar as {@code refund_total}. */
    public static TiktokRefundAmount fromNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isValueNode()) {
            return new TiktokRefundAmount(null, null, null, null, node.asText());
        }
        return new TiktokRefundAmount(text(node, "currency"), text(node, "refund_subtotal"),
                text(node, "refund_shipping_fee"), text(node, "refund_tax"), text(node, "refund_total"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static final class Deserializer extends JsonDeserializer<TiktokRefundAmount> {
        @Override
        public TiktokRefundAmount deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return fromNode(parser.readValueAsTree());
        }
    }
}

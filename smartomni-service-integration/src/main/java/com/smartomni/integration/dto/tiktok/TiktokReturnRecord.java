package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of Get Return Records ({@code GET /return_refund/202309/returns/{return_id}/records}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TiktokReturnRecord(
        @JsonProperty("create_time") Long createTime,
        @JsonProperty("description") String description,
        @JsonProperty("event") String event,
        @JsonProperty("note") String note,
        @JsonProperty("reason_text") String reasonText,
        @JsonProperty("role") String role) {
}

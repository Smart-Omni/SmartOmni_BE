package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated response from TikTok Simulator {@code POST /return_refund/202309/cancellations/search}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CancellationSearchResponse {

    @JsonProperty("next_page_token")
    private String nextPageToken;

    @JsonProperty("total_count")
    private Integer totalCount;

    @JsonProperty("cancellations")
    private List<TiktokCancellationResponse> cancellations;

    @JsonProperty("cancel_list")
    private List<TiktokCancellationResponse> cancelList;

    public List<TiktokCancellationResponse> getCancellations() {
        if (cancellations != null) {
            return cancellations;
        }
        return cancelList != null ? cancelList : List.of();
    }
}

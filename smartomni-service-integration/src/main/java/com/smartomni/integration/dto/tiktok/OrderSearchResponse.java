package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderSearchResponse {

    @JsonProperty("next_page_token")
    private String nextPageToken;

    @JsonProperty("total_count")
    private Integer totalCount;

    @JsonProperty("orders")
    private List<TiktokOrderDetailResponse> orders;

    @JsonProperty("order_list")
    private List<TiktokOrderDetailResponse> orderList;

    public List<TiktokOrderDetailResponse> getOrders() {
        if (orders != null) {
            return orders;
        }
        return orderList != null ? orderList : List.of();
    }
}

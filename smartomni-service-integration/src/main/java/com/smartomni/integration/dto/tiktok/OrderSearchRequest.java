package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderSearchRequest {

    @JsonProperty("page_size")
    @Builder.Default
    private Integer pageSize = 20;

    @JsonProperty("page_token")
    private String pageToken;

    @JsonProperty("order_status")
    private String orderStatus;

    @JsonProperty("create_time_from")
    private Long createTimeFrom;

    @JsonProperty("create_time_to")
    private Long createTimeTo;

    @JsonProperty("update_time_from")
    private Long updateTimeFrom;

    @JsonProperty("update_time_to")
    private Long updateTimeTo;

    @JsonProperty("sort_by")
    private String sortBy;

    @JsonProperty("sort_type")
    private String sortType;
}

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
public class ReturnSearchRequest {

    @JsonProperty("page_size")
    @Builder.Default
    private Integer pageSize = 20;

    @JsonProperty("page_token")
    private String pageToken;

    @JsonProperty("return_status")
    private String returnStatus;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("return_type")
    private String returnType;

    @JsonProperty("create_time_from")
    private Long createTimeFrom;

    @JsonProperty("create_time_to")
    private Long createTimeTo;
}

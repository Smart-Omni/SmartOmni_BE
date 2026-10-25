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
public class ReturnSearchResponse {

    @JsonProperty("next_page_token")
    private String nextPageToken;

    @JsonProperty("total_count")
    private Integer totalCount;

    @JsonProperty("returns")
    private List<TiktokReturnResponse> returns;

    @JsonProperty("return_list")
    private List<TiktokReturnResponse> returnList;

    public List<TiktokReturnResponse> getReturns() {
        if (returns != null) {
            return returns;
        }
        return returnList != null ? returnList : List.of();
    }
}

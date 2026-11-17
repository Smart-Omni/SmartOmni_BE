package com.smartomni.integration.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.shopee.ShopeeCallbackRequest;
import com.smartomni.integration.dto.tiktok.TiktokCallbackRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CallbackRequestJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tiktokAcceptsBothErrorDescriptionNamesAndWritesSnakeCase() throws Exception {
        for (String name : new String[] {"error_description", "errorDescription"}) {
            TiktokCallbackRequest request = mapper.readValue(
                    "{\"error\":\"access_denied\",\"" + name + "\":\"Seller denied\"}",
                    TiktokCallbackRequest.class);
            assertEquals("Seller denied", request.getErrorDescription());
            assertEquals("Seller denied", mapper.readTree(mapper.writeValueAsString(request))
                    .path("error_description").asText());
        }
    }

    @Test
    void shopeeAcceptsBothNamesForShopAndErrorDescription() throws Exception {
        for (String shopName : new String[] {"shop_id", "shopId"}) {
            for (String errorName : new String[] {"error_description", "errorDescription"}) {
                ShopeeCallbackRequest request = mapper.readValue(
                        "{\"" + shopName + "\":\"123\",\"" + errorName + "\":\"Seller denied\"}",
                        ShopeeCallbackRequest.class);
                assertEquals("123", request.getShopId());
                assertEquals("Seller denied", request.getErrorDescription());
                assertEquals("123", mapper.readTree(mapper.writeValueAsString(request))
                        .path("shop_id").asText());
            }
        }
    }
}

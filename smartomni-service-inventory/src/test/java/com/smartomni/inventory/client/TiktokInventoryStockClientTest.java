package com.smartomni.inventory.client;

import com.smartomni.inventory.entity.InventoryOutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class TiktokInventoryStockClientTest {
    @Test
    void sendsZeroWithInternalToken() {
        var builder = RestClient.builder().baseUrl("http://integration.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://integration.test/internal/jobs/push-tiktok-stock"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Job-Token", "job-token"))
                .andExpect(content().json("{\"tenantId\":1,\"skuId\":9,\"quantity\":0}"))
                .andRespond(withNoContent());

        new TiktokInventoryStockClient(builder.build(), "job-token").push(event(0));

        server.verify();
    }

    @Test
    void serverFailureIsNotReportedAsSuccess() {
        var builder = RestClient.builder().baseUrl("http://integration.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://integration.test/internal/jobs/push-tiktok-stock"))
                .andRespond(withServerError());

        assertThrows(RestClientResponseException.class,
                () -> new TiktokInventoryStockClient(builder.build(), "job-token").push(event(5)));
    }

    private InventoryOutboxEvent event(int quantity) {
        var event = new InventoryOutboxEvent();
        event.setTenantId(1L);
        event.setSkuId(9L);
        event.setQuantity(quantity);
        return event;
    }
}

package com.smartomni.inventory.client;

import com.smartomni.inventory.entity.InventoryOutboxEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Component
public class TiktokInventoryStockClient {
    private final RestClient restClient;
    private final String internalToken;

    public TiktokInventoryStockClient(
            @Value("${smartomni.services.integration-url:http://localhost:8086}") String integrationUrl,
            @Value("${smartomni.jobs.token:}") String internalToken) {
        this(buildClient(integrationUrl), internalToken);
    }

    TiktokInventoryStockClient(RestClient restClient, String internalToken) {
        this.restClient = restClient;
        this.internalToken = internalToken;
    }

    private static RestClient buildClient(String integrationUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder().baseUrl(integrationUrl).requestFactory(factory).build();
    }

    public void push(InventoryOutboxEvent event) {
        if (internalToken.isBlank()) throw new IllegalStateException("Internal stock push token is not configured");
        restClient.post().uri("/internal/jobs/push-tiktok-stock")
                .header("X-Internal-Job-Token", internalToken)
                .body(new StockPush(event.getTenantId(), event.getSkuId(), event.getQuantity()))
                .retrieve().toBodilessEntity();
    }

    private record StockPush(long tenantId, long skuId, int quantity) { }
}

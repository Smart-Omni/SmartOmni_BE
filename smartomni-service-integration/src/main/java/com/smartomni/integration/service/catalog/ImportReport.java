package com.smartomni.integration.service.catalog;

import java.time.Instant;
import java.util.List;

/** Outcome of one product import run. {@code items} lists everything that was not a plain success. */
public record ImportReport(long runId, String status, Instant startedAt, Instant finishedAt,
                           CatalogMappingStore.RunCounters counters, Integer skuLimit, boolean skuLimitReached,
                           String errorMessage, List<Item> items, int itemsOmitted) {

    public record Item(String externalProductId, String externalSkuId, String outcome, String message) {
    }
}

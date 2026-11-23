package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.service.catalog.CatalogMappingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TiktokInventoryWebhookServiceTest {
    @Mock CatalogMappingStore mappings;
    @Mock JdbcTemplate jdbc;
    TiktokInventoryWebhookService service;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        service = new TiktokInventoryWebhookService(mappings, jdbc);
    }

    @Test
    void zeroSnapshotWritesAllWarehousesAndSharedBalance() throws Exception {
        when(mappings.findLinkByExternalSku(5, "SKU-1")).thenReturn(Optional.of(
                new CatalogMappingStore.LinkRef(7, 9, 10, 5, "SKU-1", "P-1", null, null, null, null)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TiktokWebhookPayload payload = payload(0, 0, 0);

        service.apply(100, 5, payload);

        verify(jdbc).update(startsWith("INSERT INTO inventory ("), eq(100L), eq(9L), eq(0));
        verify(jdbc).update(startsWith("INSERT INTO tiktok_inventory_warehouse_snapshots"), eq(100L), eq(5L), eq(9L), eq("WH-1"), eq(0));
        verify(jdbc).update(startsWith("INSERT INTO tiktok_inventory_warehouse_snapshots"), eq(100L), eq(5L), eq(9L), eq("WH-2"), eq(0));
    }

    @Test
    void duplicateEventDoesNotTouchBalance() throws Exception {
        when(mappings.findLinkByExternalSku(5, "SKU-1")).thenReturn(Optional.of(
                new CatalogMappingStore.LinkRef(7, 9, 10, 5, "SKU-1", "P-1", null, null, null, null)));
        service.apply(100, 5, payload(0, 0, 0));
        verify(jdbc, never()).update(startsWith("INSERT INTO inventory ("), any(Object[].class));
    }

    @Test
    void inconsistentWarehouseTotalIsRejected() throws Exception {
        when(mappings.findLinkByExternalSku(5, "SKU-1")).thenReturn(Optional.of(
                new CatalogMappingStore.LinkRef(7, 9, 10, 5, "SKU-1", "P-1", null, null, null, null)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertThrows(IllegalArgumentException.class, () -> service.apply(100, 5, payload(4, 0, 0)));
    }

    private TiktokWebhookPayload payload(int total, int first, int second) throws Exception {
        var data = mapper.readTree("{\"event_id\":\"evt-1\",\"sku_id\":\"SKU-1\","
                + "\"quantity_snapshot_after_change\":{\"available_quantity\":" + total
                + ",\"warehouses\":[{\"warehouse_id\":\"WH-1\",\"available_quantity\":" + first
                + "},{\"warehouse_id\":\"WH-2\",\"available_quantity\":" + second + "}]}} ");
        return TiktokWebhookPayload.builder().type(71).ttsNotificationId("notification-1")
                .timestamp(1_794_933_200L).shopId("shop-1").data(data).build();
    }
}

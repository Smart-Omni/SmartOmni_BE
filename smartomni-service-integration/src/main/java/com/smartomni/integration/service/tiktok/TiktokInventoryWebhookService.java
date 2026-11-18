package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.service.catalog.CatalogMappingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TiktokInventoryWebhookService {
    private final CatalogMappingStore mappings;
    private final JdbcTemplate jdbc;

    /** The caller has opened the tenant scope before this transaction begins. */
    @Transactional
    public void apply(long tenantId, long connectionId, TiktokWebhookPayload payload) {
        if (!Integer.valueOf(71).equals(payload.getType()) || payload.getData() == null) {
            throw new IllegalArgumentException("Expected TikTok inventory webhook");
        }
        mappings.applyRlsContext(tenantId, "tenant_admin");
        JsonNode data = payload.getData();
        String externalSku = requiredText(data, "sku_id");
        String eventId = data.path("event_id").asText("");
        if (eventId.isBlank()) eventId = payload.getTtsNotificationId();
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Missing inventory event ID");
        JsonNode snapshot = data.path("quantity_snapshot_after_change");
        if (!snapshot.path("available_quantity").canConvertToInt()) {
            throw new IllegalArgumentException("Missing available inventory snapshot");
        }
        int total = snapshot.path("available_quantity").intValue();
        if (total < 0) throw new IllegalArgumentException("Negative inventory snapshot");
        long skuId = mappings.findLinkByExternalSku(connectionId, externalSku)
                .orElseThrow(() -> new IllegalStateException("TikTok SKU mapping missing: " + externalSku)).skuId();
        int inserted = jdbc.update("INSERT INTO tiktok_inventory_webhook_events (tenant_id, connection_id, event_id) "
                        + "VALUES (?, ?, ?) ON CONFLICT (tenant_id, connection_id, event_id) DO NOTHING",
                tenantId, connectionId, eventId);
        if (inserted == 0) return;

        long timestamp = payload.getTimestamp() == null ? 0 : payload.getTimestamp();
        if (timestamp <= 0) throw new IllegalArgumentException("Missing inventory event timestamp");
        int accepted = jdbc.update("INSERT INTO tiktok_inventory_sku_snapshots "
                        + "(tenant_id, connection_id, sku_id, available_quantity, observed_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (connection_id, sku_id) DO UPDATE SET available_quantity = EXCLUDED.available_quantity, "
                        + "observed_at = EXCLUDED.observed_at WHERE tiktok_inventory_sku_snapshots.observed_at <= EXCLUDED.observed_at",
                tenantId, connectionId, skuId, total, java.sql.Timestamp.from(Instant.ofEpochSecond(timestamp)));
        if (accepted == 0) return;

        JsonNode warehouses = snapshot.path("warehouses");
        jdbc.update("DELETE FROM tiktok_inventory_warehouse_snapshots WHERE tenant_id = ? AND connection_id = ? AND sku_id = ?",
                tenantId, connectionId, skuId);
        if (warehouses.isArray() && !warehouses.isEmpty()) {
            long sum = 0;
            Set<String> seen = new HashSet<>();
            for (JsonNode warehouse : warehouses) {
                String warehouseId = requiredText(warehouse, "warehouse_id");
                if (!seen.add(warehouseId) || !warehouse.path("available_quantity").canConvertToInt()) {
                    throw new IllegalArgumentException("Invalid warehouse inventory snapshot");
                }
                int quantity = warehouse.path("available_quantity").intValue();
                if (quantity < 0) throw new IllegalArgumentException("Negative warehouse inventory");
                sum += quantity;
            }
            if (sum != total) throw new IllegalArgumentException("Warehouse inventory does not match SKU total");
            for (JsonNode warehouse : warehouses) {
                jdbc.update("INSERT INTO tiktok_inventory_warehouse_snapshots "
                                + "(tenant_id, connection_id, sku_id, external_warehouse_id, available_quantity) VALUES (?, ?, ?, ?, ?)",
                        tenantId, connectionId, skuId, warehouse.path("warehouse_id").asText(),
                        warehouse.path("available_quantity").intValue());
            }
        }

        jdbc.update("INSERT INTO inventory (tenant_id, sku_id, quantity_on_hand, reserved_quantity, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, 0, now(), now()) ON CONFLICT (sku_id) DO UPDATE "
                        + "SET quantity_on_hand = EXCLUDED.quantity_on_hand, version = inventory.version + 1, updated_at = now() "
                        + "WHERE inventory.tenant_id = EXCLUDED.tenant_id AND inventory.quantity_on_hand IS DISTINCT FROM EXCLUDED.quantity_on_hand",
                tenantId, skuId, total);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }
}

package com.smartomni.inventory.repository;

import com.smartomni.common.dto.InventoryItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class SkuOwnershipRepository {
    private final JdbcTemplate jdbcTemplate;

    public boolean exists(Long tenantId, Long skuId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM product_skus WHERE tenant_id = ? AND id = ?)",
                Boolean.class, tenantId, skuId));
    }

    public List<InventoryItemDto> listEnrichedInventory(Long tenantId, int limit, int offset) {
        String sql = """
            SELECT s.id AS sku_id,
                   s.sku_code AS sku_code,
                   COALESCE(p.name, 'Unknown Product') AS product_name,
                   COALESCE(i.quantity_on_hand, 0) AS quantity_on_hand,
                   COALESCE(i.reserved_quantity, 0) AS reserved_quantity,
                   (i.sku_id IS NOT NULL) AS inventory_initialized
            FROM product_skus s
            LEFT JOIN inventory i ON i.sku_id = s.id AND i.tenant_id = s.tenant_id
            LEFT JOIN products p ON s.product_id = p.id AND p.tenant_id = s.tenant_id
            WHERE s.tenant_id = ?
            ORDER BY s.id ASC
            LIMIT ? OFFSET ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            long skuId = rs.getLong("sku_id");
            String skuCode = rs.getString("sku_code");
            String productName = rs.getString("product_name");
            int onHand = rs.getInt("quantity_on_hand");
            int reserved = rs.getInt("reserved_quantity");
            int available = Math.max(0, onHand - reserved);
            return InventoryItemDto.builder()
                    .skuId(skuId)
                    .skuCode(skuCode)
                    .productName(productName)
                    .quantityOnHand(onHand)
                    .reservedQuantity(reserved)
                    .availableQuantity(available)
                    .inventoryInitialized(rs.getBoolean("inventory_initialized"))
                    .build();
        }, tenantId, limit, offset);
    }

    public long countEnrichedInventory(Long tenantId) {
        String sql = """
            SELECT COUNT(*) FROM product_skus WHERE tenant_id = ?
            """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, tenantId);
        return count != null ? count : 0L;
    }
}

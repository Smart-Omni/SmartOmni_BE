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
            SELECT COALESCE(s.id, i.sku_id) AS sku_id,
                   COALESCE(s.sku_code, CONCAT('SKU-', COALESCE(s.id, i.sku_id))) AS sku_code,
                   COALESCE(p.name, 'Unknown Product') AS product_name,
                   COALESCE(i.quantity_on_hand, 0) AS quantity_on_hand,
                   COALESCE(i.reserved_quantity, 0) AS reserved_quantity
            FROM product_skus s
            FULL OUTER JOIN inventory i ON i.sku_id = s.id AND i.tenant_id = s.tenant_id
            LEFT JOIN products p ON s.product_id = p.id
            WHERE COALESCE(s.tenant_id, i.tenant_id) = ?
            ORDER BY COALESCE(s.id, i.sku_id) ASC
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
                    .build();
        }, tenantId, limit, offset);
    }

    public long countEnrichedInventory(Long tenantId) {
        String sql = """
            SELECT COUNT(DISTINCT COALESCE(s.id, i.sku_id))
            FROM product_skus s
            FULL OUTER JOIN inventory i ON i.sku_id = s.id AND i.tenant_id = s.tenant_id
            WHERE COALESCE(s.tenant_id, i.tenant_id) = ?
            """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, tenantId);
        return count != null ? count : 0L;
    }
}

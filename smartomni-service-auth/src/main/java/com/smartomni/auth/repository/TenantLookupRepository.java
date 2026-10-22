package com.smartomni.auth.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository tra cứu thông tin Tenant trực tiếp từ bảng tenants thông qua EntityManager,
 * tránh xung đột Entity name giữa các microservices khi chạy migration tests.
 */
@Repository
@RequiredArgsConstructor
public class TenantLookupRepository {

    private final EntityManager entityManager;

    public Optional<TenantLookup> findBySubdomain(String subdomain) {
        List<?> list = entityManager.createNativeQuery(
                "SELECT id, name, subdomain, status, locked_reason FROM tenants WHERE subdomain = :subdomain")
                .setParameter("subdomain", subdomain)
                .getResultList();

        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }

        Object[] row = (Object[]) list.get(0);
        return Optional.of(new TenantLookup(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                row[3] != null ? row[3].toString() : "active",
                (String) row[4]
        ));
    }

    public Optional<TenantLookup> findById(Long tenantId) {
        List<?> list = entityManager.createNativeQuery(
                "SELECT id, name, subdomain, status, locked_reason FROM tenants WHERE id = :tenantId")
                .setParameter("tenantId", tenantId)
                .getResultList();

        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }

        Object[] row = (Object[]) list.get(0);
        return Optional.of(new TenantLookup(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                row[3] != null ? row[3].toString() : "active",
                (String) row[4]
        ));
    }

    public record TenantLookup(Long id, String name, String subdomain, String status, String lockedReason) {
        public boolean isLocked() {
            return "locked".equalsIgnoreCase(status);
        }
    }
}

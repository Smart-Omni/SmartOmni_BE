package com.smartomni.integration.repository;

import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.WebhookEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface WebhookEventLogRepository extends JpaRepository<WebhookEventLog, Long> {
    Optional<WebhookEventLog> findByTenantIdAndPlatformAndEventId(Long tenantId, MarketplaceConnection.Platform platform, String eventId);
}

package com.smartomni.integration.repository;

import com.smartomni.integration.entity.MarketplaceAuthorizationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Transactional(readOnly = true)
public interface MarketplaceAuthorizationStateRepository extends JpaRepository<MarketplaceAuthorizationState, Long> {

    Optional<MarketplaceAuthorizationState> findByTenantIdAndStateHash(Long tenantId, String stateHash);

    /**
     * Atomic single-use consumption: exactly one concurrent caller gets 1, every other caller (repeat, race,
     * wrong user, expired) gets 0 and no side effect.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MarketplaceAuthorizationState s set s.consumedAt = :now, s.updatedAt = :now "
            + "where s.tenantId = :tenantId and s.stateHash = :hash and s.userId = :userId "
            + "and s.consumedAt is null and s.expiresAt > :now")
    int consume(@Param("tenantId") Long tenantId, @Param("hash") String hash,
                @Param("userId") Long userId, @Param("now") Instant now);
}

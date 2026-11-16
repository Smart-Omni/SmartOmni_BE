package com.smartomni.ai.repository;

import com.smartomni.ai.entity.DemandForecast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

@org.springframework.transaction.annotation.Transactional(readOnly = true)
public interface DemandForecastRepository extends JpaRepository<DemandForecast, Long> {
    List<DemandForecast> findByTenantIdAndSkuId(Long tenantId, Long skuId);

    List<DemandForecast> findByTenantIdAndSkuIdOrderByForecastDateAsc(Long tenantId, Long skuId);

    List<DemandForecast> findByTenantIdAndSkuIdAndForecastDateGreaterThanEqualOrderByForecastDateAsc(
            Long tenantId, Long skuId, LocalDate fromDate);

    List<DemandForecast> findByTenantIdAndSkuIdAndForecastDateBetweenOrderByForecastDateAsc(
            Long tenantId, Long skuId, LocalDate fromDate, LocalDate toDate);
}

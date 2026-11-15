package com.smartomni.ai.service;

import com.smartomni.ai.client.PythonAiServiceClient;
import com.smartomni.ai.dto.ForecastResultDto;
import com.smartomni.ai.entity.AiModelVersion;
import com.smartomni.ai.entity.DemandForecast;
import com.smartomni.ai.repository.AiModelVersionRepository;
import com.smartomni.ai.repository.DemandForecastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * UC-14: Nhan canh bao ton kho tu AI (Manager doc ket qua o day)
 * UC-35: Du bao nhu cau hang hoa (dieu phoi goi Python AI Service)
 * UC-37: Huan luyen & Rollback mo hinh AI theo Tenant - FR-069, FR-070
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandForecastService {

    private final PythonAiServiceClient pythonAiServiceClient;
    private final DemandForecastRepository demandForecastRepository;
    private final AiModelVersionRepository modelVersionRepository;

    @Transactional
    public void runForecastForTenant(Long tenantId) {
        List<ForecastResultDto> results = pythonAiServiceClient.requestDemandForecast(tenantId);
        if (results.isEmpty()) return;

        // FR-066, FR-069: so sanh RMSE mo hinh moi voi mo hinh dang ACTIVE, rollback neu te hon
        AiModelVersion currentActive = modelVersionRepository
                .findByTenantIdAndModelTypeAndStatus(tenantId, AiModelVersion.ModelType.DEMAND_FORECASTING, AiModelVersion.ModelStatus.ACTIVE)
                .orElse(null);

        var firstResult = results.get(0);
        AiModelVersion newVersion = new AiModelVersion();
        newVersion.setTenantId(tenantId);
        newVersion.setModelType(AiModelVersion.ModelType.DEMAND_FORECASTING);
        newVersion.setVersionNumber(firstResult.getModelVersion());
        newVersion.setRmse(firstResult.getRmse());
        newVersion.setTrainedAt(Instant.now());

        boolean isBetter = currentActive == null || currentActive.getRmse() == null
                || (firstResult.getRmse() != null && firstResult.getRmse().compareTo(currentActive.getRmse()) < 0);

        if (isBetter) {
            newVersion.setStatus(AiModelVersion.ModelStatus.ACTIVE);
            if (currentActive != null) {
                currentActive.setStatus(AiModelVersion.ModelStatus.ROLLED_BACK);
                modelVersionRepository.save(currentActive);
            }
            log.info("Mo hinh Demand Forecasting moi tot hon (RMSE={}), kich hoat cho tenant={}",
                    firstResult.getRmse(), tenantId);
        } else {
            newVersion.setStatus(AiModelVersion.ModelStatus.ROLLED_BACK);
            log.info("Mo hinh moi te hon mo hinh hien tai (RMSE moi={} >= RMSE cu={}), giu nguyen mo hinh cu cho tenant={}",
                    firstResult.getRmse(), currentActive.getRmse(), tenantId);
        }
        AiModelVersion savedVersion = modelVersionRepository.save(newVersion);

        // FR-067: luu ket qua du bao + de xuat so luong nhap kho (chi luu neu model duoc active)
        if (isBetter) {
            for (ForecastResultDto result : results) {
                DemandForecast forecast = new DemandForecast();
                forecast.setTenantId(tenantId);
                forecast.setSkuId(result.getSkuId());
                forecast.setModelVersionId(savedVersion.getId());
                forecast.setForecastDate(result.getForecastDate());
                forecast.setPredictedQuantity(result.getPredictedQuantity());
                forecast.setRecommendedReorderQty(result.getRecommendedReorderQty());
                demandForecastRepository.save(forecast);
                // TODO: neu recommendedReorderQty > 0, day thong bao/canh bao cho Manager (UC-14)
            }
        }
    }

    public List<DemandForecast> getForecastsForSku(Long tenantId, Long skuId) {
        return getForecastsForSku(tenantId, skuId, null, null);
    }

    public List<DemandForecast> getForecastsForSku(Long tenantId, Long skuId, LocalDate fromDate, Integer days) {
        LocalDate effectiveFrom = fromDate != null ? fromDate : LocalDate.now();
        if (days != null && days > 0) {
            LocalDate toDate = effectiveFrom.plusDays(days);
            return demandForecastRepository.findByTenantIdAndSkuIdAndForecastDateBetweenOrderByForecastDateAsc(
                    tenantId, skuId, effectiveFrom, toDate);
        }
        return demandForecastRepository.findByTenantIdAndSkuIdAndForecastDateGreaterThanEqualOrderByForecastDateAsc(
                tenantId, skuId, effectiveFrom);
    }
}

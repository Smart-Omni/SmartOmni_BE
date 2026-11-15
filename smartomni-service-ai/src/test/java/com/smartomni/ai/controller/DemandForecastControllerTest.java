package com.smartomni.ai.controller;

import com.smartomni.ai.entity.DemandForecast;
import com.smartomni.ai.service.DemandForecastService;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DemandForecastController Unit Tests")
class DemandForecastControllerTest {

    private DemandForecastService service;
    private DemandForecastController controller;

    @BeforeEach
    void setUp() {
        service = mock(DemandForecastService.class);
        controller = new DemandForecastController(service);
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getForecastsWithDefaultParams() {
        DemandForecast df = new DemandForecast();
        df.setSkuId(55L);
        df.setForecastDate(LocalDate.now());
        df.setPredictedQuantity(15);

        when(service.getForecastsForSku(1L, 55L, null, null)).thenReturn(List.of(df));

        ApiResponse<List<DemandForecast>> response = controller.getForecasts(55L, null, null);

        assertNotNull(response);
        assertEquals(1, response.getData().size());
        assertEquals(15, response.getData().get(0).getPredictedQuantity());
        verify(service).getForecastsForSku(1L, 55L, null, null);
    }

    @Test
    void getForecastsWithFromAndDays() {
        LocalDate fromDate = LocalDate.parse("2026-11-20");
        DemandForecast df = new DemandForecast();
        df.setSkuId(55L);
        df.setForecastDate(fromDate);
        df.setPredictedQuantity(25);

        when(service.getForecastsForSku(1L, 55L, fromDate, 7)).thenReturn(List.of(df));

        ApiResponse<List<DemandForecast>> response = controller.getForecasts(55L, fromDate, 7);

        assertNotNull(response);
        assertEquals(1, response.getData().size());
        verify(service).getForecastsForSku(1L, 55L, fromDate, 7);
    }
}

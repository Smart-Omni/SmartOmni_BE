package com.smartomni.ai.service;

import com.smartomni.ai.dto.insights.SalesInsightsResponse;
import com.smartomni.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalesInsightsServiceTest {

    @Test
    void rejectsUnsupportedPeriodDays() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SalesInsightsService service = new SalesInsightsService(jdbc, Clock.fixed(Instant.parse("2026-11-28T03:00:00Z"), Clock.systemUTC().getZone()));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getSalesInsights(1L, 14));
        assertEquals("INVALID_PERIOD", ex.getErrorCode());
    }

    @Test
    void computes7DaySalesInsightsAndPeriodLabelInGmtPlus7() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        // 2026-11-28T03:00:00Z = 2026-11-28 10:00 GMT+7 -> 7-day window 22/11/2026 - 28/11/2026
        Clock fixed = Clock.fixed(Instant.parse("2026-11-28T03:00:00Z"), Clock.systemUTC().getZone());
        SalesInsightsService service = new SalesInsightsService(jdbc, fixed);

        doNothing().when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(new SalesInsightsResponse.DeductionsBreakdown(
                        new BigDecimal("10000"), new BigDecimal("5000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("15000"), BigDecimal.ZERO, false, 1L));

        SalesInsightsResponse res = service.getSalesInsights(1L, 7);

        assertEquals(7, res.periodDays());
        assertEquals("22/11/2026 – 28/11/2026", res.periodLabel());
        assertEquals("Asia/Ho_Chi_Minh", res.timezone());
        assertEquals(7, res.chart().size());
        assertEquals(7, res.shopee().orderSeries().size());
        assertEquals(7, res.tiktok().orderSeries().size());
        assertEquals(new BigDecimal("10000"), res.shopee().deductions().platformFees());
    }
}

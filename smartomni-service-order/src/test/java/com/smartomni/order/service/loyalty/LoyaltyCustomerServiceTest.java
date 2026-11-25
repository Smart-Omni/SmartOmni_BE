package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.ClaimPointsRequest;
import com.smartomni.order.dto.loyalty.LoyaltyClaimDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoyaltyCustomerServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private LoyaltyCustomerService service;

    @Test
    void rejectsMissingTenantOrUserContext() {
        assertThatThrownBy(() -> service.claimOrderPoints(10L, null, new ClaimPointsRequest(100L, BigDecimal.valueOf(1000))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.claimOrderPoints(null, 1L, new ClaimPointsRequest(100L, BigDecimal.valueOf(1000))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void rejectsMissingOrNonPositiveTotalAmount() {
        assertThatThrownBy(() -> service.claimOrderPoints(10L, 1L, new ClaimPointsRequest(100L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo("INVALID_TOTAL_AMOUNT");
                    assertThat(be.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        assertThatThrownBy(() -> service.claimOrderPoints(10L, 1L, new ClaimPointsRequest(100L, BigDecimal.ZERO)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INVALID_TOTAL_AMOUNT"));
    }

    @Test
    void rejectsNonCustomerRole() {
        when(jdbcTemplate.query(contains("FROM users"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of("manager"));

        assertThatThrownBy(() -> service.claimOrderPoints(10L, 1L, new ClaimPointsRequest(100L, BigDecimal.valueOf(250000))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("FORBIDDEN_CUSTOMER_ROLE"));
    }

    @Test
    void rejectsEarlyClaimBefore15Days() {
        when(jdbcTemplate.query(contains("FROM users"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of("customer"));
        mockOrderRow(100L, "completed", BigDecimal.valueOf(500000), Instant.now().plus(3, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.claimOrderPoints(10L, 1L, new ClaimPointsRequest(100L, BigDecimal.valueOf(500000))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ORDER_NOT_ELIGIBLE_YET"));
    }

    @Test
    void rejectsAmountMismatchWhenProvidedByClient() {
        when(jdbcTemplate.query(contains("FROM users"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of("customer"));
        mockOrderRow(100L, "completed", BigDecimal.valueOf(250000), Instant.now().minus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.claimOrderPoints(10L, 1L,
                new ClaimPointsRequest(100L, BigDecimal.valueOf(300000))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ORDER_AMOUNT_MISMATCH"));
    }

    @Test
    void rejectsOrderWithApprovedReturn() {
        when(jdbcTemplate.query(contains("FROM users"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of("customer"));
        mockOrderRow(100L, "completed", BigDecimal.valueOf(250000), Instant.now().minus(1, ChronoUnit.DAYS));
        when(jdbcTemplate.queryForObject(contains("FROM returns_refunds"), eq(Integer.class), eq(1L), eq(100L)))
                .thenReturn(1);

        assertThatThrownBy(() -> service.claimOrderPoints(10L, 1L, new ClaimPointsRequest(100L, BigDecimal.valueOf(250000))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ORDER_HAS_APPROVED_RETURN"));
    }

    @Test
    void rejectsConcurrentDuplicateClaimWithConflict() {
        when(jdbcTemplate.query(contains("FROM users"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of("customer"));
        mockOrderRow(100L, "completed", BigDecimal.valueOf(250000), Instant.now().minus(1, ChronoUnit.DAYS));
        when(jdbcTemplate.queryForObject(contains("FROM returns_refunds"), eq(Integer.class), eq(1L), eq(100L)))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("FROM loyalty_claims"), eq(Integer.class), eq(1L), eq(100L)))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO loyalty_claims"), any(RowMapper.class),
                eq(1L), eq(100L), eq(10L), eq(250L)))
                .thenThrow(new DataIntegrityViolationException("uq_loyalty_claims_order"));

        assertThatThrownBy(() -> service.claimOrderPoints(10L, 1L, new ClaimPointsRequest(100L, BigDecimal.valueOf(250000))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo("ORDER_ALREADY_CLAIMED");
                    assertThat(be.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void claimsPointsSuccessfullyWhenAmountMatches() {
        when(jdbcTemplate.query(contains("FROM users"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of("customer"));
        mockOrderRow(100L, "completed", BigDecimal.valueOf(250500), Instant.now().minus(1, ChronoUnit.DAYS));
        when(jdbcTemplate.queryForObject(contains("FROM returns_refunds"), eq(Integer.class), eq(1L), eq(100L)))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("FROM loyalty_claims"), eq(Integer.class), eq(1L), eq(100L)))
                .thenReturn(0);
        LoyaltyClaimDto expected = LoyaltyClaimDto.builder()
                .id(7L)
                .orderId(100L)
                .customerId(10L)
                .eligibleAmountVnd(BigDecimal.valueOf(250500))
                .pointsAwarded(250L)
                .claimedAt(Instant.now())
                .build();
        when(jdbcTemplate.queryForObject(contains("INSERT INTO loyalty_claims"), any(RowMapper.class),
                eq(1L), eq(100L), eq(10L), eq(250L)))
                .thenReturn(expected);

        LoyaltyClaimDto actual = service.claimOrderPoints(10L, 1L,
                new ClaimPointsRequest(100L, BigDecimal.valueOf(250500)));

        assertThat(actual.getPointsAwarded()).isEqualTo(250L);
        assertThat(actual.getOrderId()).isEqualTo(100L);
    }

    private void mockOrderRow(Long orderId, String status, BigDecimal totalAmount, Instant eligibleAt) {
        when(jdbcTemplate.query(contains("FROM orders"), any(RowMapper.class), eq(1L), eq(orderId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(orderId);
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getBigDecimal("total_amount")).thenReturn(totalAmount);
                    when(rs.getTimestamp("eligible_for_points_at"))
                            .thenReturn(eligibleAt != null ? Timestamp.from(eligibleAt) : null);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }
}

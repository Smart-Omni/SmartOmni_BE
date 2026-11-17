package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.CreateRewardRequest;
import com.smartomni.order.dto.loyalty.IntegrationIssueVoucherResponse;
import com.smartomni.order.dto.loyalty.LoyaltyRewardRequestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoyaltyCampaignAndRewardServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private LoyaltyVoucherWorker voucherWorker;

    @Mock
    private IntegrationVoucherClient integrationVoucherClient;

    @Test
    void customerRewardRequestFailsWithDetailedErrorWhenInsufficientPoints() {
        LoyaltyRewardService rewardService = new LoyaltyRewardService(jdbcTemplate, voucherWorker);
        UUID key = UUID.randomUUID();

        when(jdbcTemplate.query(contains("r.request_key = ?"), any(RowMapper.class), eq(1L), eq(key)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM users"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of("customer"));
        mockCampaignRow(5L, 500L, 10, 0, "active", "connected");
        when(jdbcTemplate.queryForObject(contains("FROM loyalty_reward_requests"), eq(Integer.class),
                eq(1L), eq(5L), eq(10L)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM loyalty_point_accounts"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of(320L));

        assertThatThrownBy(() -> rewardService.requestReward(10L, 1L,
                new CreateRewardRequest(5L, key.toString())))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo("INSUFFICIENT_LOYALTY_POINTS");
                    assertThat(be.getMessage()).contains("320").contains("500");
                });
    }

    @Test
    void customerRewardRequestSucceedsAndDispatchesAsyncWorker() {
        LoyaltyRewardService rewardService = new LoyaltyRewardService(jdbcTemplate, voucherWorker);
        UUID key = UUID.randomUUID();

        when(jdbcTemplate.query(contains("r.request_key = ?"), any(RowMapper.class), eq(1L), eq(key)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM users"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of("customer"));
        mockCampaignRow(5L, 500L, 10, 2, "active", "connected");
        when(jdbcTemplate.queryForObject(contains("FROM loyalty_reward_requests"), eq(Integer.class),
                eq(1L), eq(5L), eq(10L)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM loyalty_point_accounts"), any(RowMapper.class), eq(1L), eq(10L)))
                .thenReturn(List.of(650L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO loyalty_reward_requests"), eq(Long.class),
                eq(1L), eq(5L), eq(10L), eq(key)))
                .thenReturn(99L);

        LoyaltyRewardRequestDto createdDto = LoyaltyRewardRequestDto.builder()
                .id(99L)
                .tenantId(1L)
                .campaignId(5L)
                .customerId(10L)
                .requestKey(key.toString())
                .status("pending")
                .build();
        when(jdbcTemplate.queryForObject(contains("WHERE r.tenant_id = ? AND r.id = ?"), any(RowMapper.class),
                eq(1L), eq(99L)))
                .thenReturn(createdDto);

        LoyaltyRewardRequestDto res = rewardService.requestReward(10L, 1L,
                new CreateRewardRequest(5L, key.toString()));

        assertThat(res.getStatus()).isEqualTo("pending");
        assertThat(res.getId()).isEqualTo(99L);
        verify(voucherWorker).processRequestAsync(1L, 99L);
    }

    @Test
    void workerTransitionsToIssuedAndInsertsNotificationOnMarketplaceSuccess() {
        TransactionTemplate txTemplate = mockImmediateTxTemplate();
        LoyaltyVoucherWorker worker = new LoyaltyVoucherWorker(jdbcTemplate, txTemplate, integrationVoucherClient);

        mockPendingWorkerRow(1L, 99L);
        when(integrationVoucherClient.issueVoucher(eq(1L), any()))
                .thenReturn(IntegrationIssueVoucherResponse.builder()
                        .outcome("ISSUED")
                        .externalVoucherId("TT-VCH-1")
                        .code("SILVER10")
                        .build());
        when(jdbcTemplate.update(contains("SET status = 'issued'"), eq("SILVER10"), eq("TT-VCH-1"), eq(1L), eq(99L)))
                .thenReturn(1);

        worker.processPendingRequest(1L, 99L);

        verify(jdbcTemplate).update(contains("SET status = 'issued'"), eq("SILVER10"), eq("TT-VCH-1"), eq(1L), eq(99L));
        verify(jdbcTemplate).update(contains("INSERT INTO loyalty_notification_events"),
                eq(1L), eq(99L), eq(10L), anyString());
    }

    @Test
    void workerTransitionsToFailedOnDefinitiveMarketplaceRejection() {
        TransactionTemplate txTemplate = mockImmediateTxTemplate();
        LoyaltyVoucherWorker worker = new LoyaltyVoucherWorker(jdbcTemplate, txTemplate, integrationVoucherClient);

        mockPendingWorkerRow(1L, 99L);
        when(integrationVoucherClient.issueVoucher(eq(1L), any()))
                .thenReturn(IntegrationIssueVoucherResponse.builder()
                        .outcome("FAILED")
                        .failureReason("TikTok rejected voucher")
                        .build());

        worker.processPendingRequest(1L, 99L);

        verify(jdbcTemplate).update(contains("SET status = 'failed'"),
                eq("TikTok rejected voucher"), eq(1L), eq(99L));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO loyalty_notification_events"),
                any(), any(), any(), any());
    }

    @Test
    void workerLeavesPendingOnAmbiguousTimeout() {
        TransactionTemplate txTemplate = mockImmediateTxTemplate();
        LoyaltyVoucherWorker worker = new LoyaltyVoucherWorker(jdbcTemplate, txTemplate, integrationVoucherClient);

        mockPendingWorkerRow(1L, 99L);
        when(integrationVoucherClient.issueVoucher(eq(1L), any()))
                .thenReturn(IntegrationIssueVoucherResponse.builder()
                        .outcome("PENDING")
                        .failureReason("Read timed out")
                        .build());

        worker.processPendingRequest(1L, 99L);

        verify(jdbcTemplate, never()).update(contains("SET status ="), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO loyalty_notification_events"),
                any(), any(), any(), any());
    }

    private void mockCampaignRow(Long id, long requiredPoints, int qty, int reserved, String status, String connStatus) {
        when(jdbcTemplate.query(contains("FROM loyalty_reward_campaigns c"), any(RowMapper.class), eq(1L), eq(id)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(id);
                    when(rs.getLong("required_points")).thenReturn(requiredPoints);
                    when(rs.getInt("quantity")).thenReturn(qty);
                    when(rs.getInt("reserved_count")).thenReturn(reserved);
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getTimestamp("starts_at"))
                            .thenReturn(Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
                    when(rs.getTimestamp("ends_at"))
                            .thenReturn(Timestamp.from(Instant.now().plus(10, ChronoUnit.DAYS)));
                    when(rs.getString("conn_status")).thenReturn(connStatus);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void mockPendingWorkerRow(Long tenantId, Long requestId) {
        when(jdbcTemplate.query(contains("FROM loyalty_reward_requests r"), any(RowMapper.class),
                eq(tenantId), eq(requestId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(requestId);
                    when(rs.getLong("customer_id")).thenReturn(10L);
                    when(rs.getString("request_key")).thenReturn(UUID.randomUUID().toString());
                    when(rs.getLong("connection_id")).thenReturn(3L);
                    when(rs.getString("campaign_name")).thenReturn("Silver 10%");
                    when(rs.getBigDecimal("discount_percent")).thenReturn(BigDecimal.TEN);
                    when(rs.getString("provisioning_mode")).thenReturn("import");
                    when(rs.getString("external_coupon_id")).thenReturn(null);
                    when(rs.getTimestamp("starts_at")).thenReturn(Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
                    when(rs.getTimestamp("ends_at")).thenReturn(Timestamp.from(Instant.now().plus(10, ChronoUnit.DAYS)));
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private TransactionTemplate mockImmediateTxTemplate() {
        TransactionTemplate txTemplate = mock(TransactionTemplate.class);
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        return txTemplate;
    }
}

package com.smartomni.order.entity;

import org.hibernate.annotations.JdbcType;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** UC-13: Quan ly hoan/huy don hang tap trung da san. FR-030, WF-05, DS-09. */
@Getter
@Setter
@Entity
@Table(name = "returns_refunds")
public class ReturnRefund extends BaseTenantEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "external_return_id")
    private String externalReturnId;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(name = "platform", columnDefinition = "platform_type")
    private Order.Platform platform;

    @Column(name = "return_type")
    private String returnType;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(name = "trigger_type", nullable = false, columnDefinition = "return_trigger_type")
    private TriggerType triggerType = TriggerType.CUSTOMER_REQUEST;

    @Column(name = "return_tracking_number")
    private String returnTrackingNumber;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "refund_status")
    private RefundStatus status = RefundStatus.REQUESTED;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "video_evidence_url")
    private String videoEvidenceUrl;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(name = "inspection_result", columnDefinition = "inspection_result_type")
    private InspectionResultType inspectionResult = InspectionResultType.PENDING;

    @Column(name = "restocked")
    private boolean restocked = false;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(name = "dispute_status", columnDefinition = "dispute_status_type")
    private DisputeStatusType disputeStatus = DisputeStatusType.NONE;

    @Column(name = "compensation_amount")
    private BigDecimal compensationAmount;

    @Column(name = "refund_amount")
    private BigDecimal refundAmount;

    public enum TriggerType { REFUSED_DELIVERY, CUSTOMER_REQUEST }

    public enum RefundStatus { REQUESTED, APPROVED, REJECTED, COMPLETED }

    public enum InspectionResultType { PENDING, MATCHED, DAMAGED, SWAPPED, MISSING_ITEMS }

    public enum DisputeStatusType { NONE, FILED, APPROVED, DENIED }
}

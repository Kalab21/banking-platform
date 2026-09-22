package com.bankingplatform.application.model;

import com.bankingplatform.application.underwriting.ReasonCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What was decided, on what figures, under which policy — kept as it was.
 *
 * <p>A decision explained from the customer's current profile is not the
 * decision that was taken. Scores move, income is updated, debts are paid down;
 * an application refused last month for a ratio that is fine today would appear
 * to have been refused for no reason. So the inputs are copied here at the
 * moment of the decision and never touched again.
 *
 * <p>There are deliberately no setters. The entity is written once by
 * {@code ApplicationServiceImpl} and read thereafter, and the table has no
 * update path in the application code. A later decision on the same
 * application — a referral that a reviewer then approves — is a new row, so the
 * history of an application is the rows in order rather than one row that has
 * been overwritten.
 */
@Entity
@Table(name = "decision_snapshots")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    /** Which policy's thresholds these figures were measured against. */
    @Column(name = "policy_version", nullable = false, updatable = false, length = 32)
    private String policyVersion;

    /** Who decided: the policy itself, or a named reviewer. */
    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", nullable = false, updatable = false, length = 16)
    private DecidedBy decidedBy;

    @Column(name = "reviewer_id", updatable = false)
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Decision decision;

    @Column(name = "credit_score_at_decision", updatable = false)
    private Integer creditScoreAtDecision;

    @Column(name = "kyc_status_at_decision", updatable = false, length = 32)
    private String kycStatusAtDecision;

    @Column(name = "annual_income_at_decision", updatable = false, precision = 19, scale = 2)
    private BigDecimal annualIncomeAtDecision;

    @Column(name = "monthly_debt_at_decision", updatable = false, precision = 19, scale = 2)
    private BigDecimal monthlyDebtAtDecision;

    /** Carried at four decimal places, as the policy compares it. */
    @Column(name = "dti_at_decision", updatable = false, precision = 9, scale = 4)
    private BigDecimal dtiAtDecision;

    @Column(name = "ltv_at_decision", updatable = false, precision = 9, scale = 4)
    private BigDecimal ltvAtDecision;

    @Column(name = "asset_value_at_decision", updatable = false, precision = 19, scale = 2)
    private BigDecimal assetValueAtDecision;

    @Column(name = "requested_amount_at_decision", updatable = false, precision = 19, scale = 2)
    private BigDecimal requestedAmountAtDecision;

    @Column(name = "requested_term_at_decision", updatable = false)
    private Integer requestedTermAtDecision;

    /** What may be lent. Null on a refusal, and on a referral not yet decided. */
    @Column(name = "approved_amount", updatable = false, precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "decision_snapshot_reasons",
            joinColumns = @JoinColumn(name = "decision_snapshot_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 48)
    @Builder.Default
    private List<ReasonCode> reasonCodes = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "decided_at", nullable = false, updatable = false)
    private LocalDateTime decidedAt;

    /** Deliberately narrower than {@code ApplicationStatus}: a decision is one of three answers. */
    public enum Decision { APPROVE, REJECT, REFER }

    public enum DecidedBy { POLICY, REVIEWER }
}

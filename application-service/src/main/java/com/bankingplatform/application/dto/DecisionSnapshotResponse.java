package com.bankingplatform.application.dto;

import com.bankingplatform.application.model.DecisionSnapshot;
import com.bankingplatform.application.underwriting.ReasonCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A decision as a reviewer needs to see it: what was decided, on what figures,
 * under which policy.
 *
 * <p>Staff-only. A reviewer looking at a referred application was previously
 * shown the application and a line of prose, and had to take on trust that a
 * policy had been applied at all — the score, the ratios and the reasons were
 * recorded but never surfaced. Deciding without them is guessing.
 */
public record DecisionSnapshotResponse(
        Long decisionId,
        Long applicationId,
        String policyVersion,
        String decidedBy,
        Long reviewerId,
        String decision,
        Integer creditScoreAtDecision,
        String kycStatusAtDecision,
        BigDecimal annualIncomeAtDecision,
        BigDecimal monthlyDebtAtDecision,
        BigDecimal dtiAtDecision,
        BigDecimal ltvAtDecision,
        BigDecimal assetValueAtDecision,
        BigDecimal requestedAmountAtDecision,
        Integer requestedTermAtDecision,
        BigDecimal approvedAmount,
        List<ReasonCode> reasonCodes,
        LocalDateTime decidedAt) {

    public static DecisionSnapshotResponse from(DecisionSnapshot snapshot) {
        return new DecisionSnapshotResponse(
                snapshot.getId(),
                snapshot.getApplicationId(),
                snapshot.getPolicyVersion(),
                snapshot.getDecidedBy().name(),
                snapshot.getReviewerId(),
                snapshot.getDecision().name(),
                snapshot.getCreditScoreAtDecision(),
                snapshot.getKycStatusAtDecision(),
                snapshot.getAnnualIncomeAtDecision(),
                snapshot.getMonthlyDebtAtDecision(),
                snapshot.getDtiAtDecision(),
                snapshot.getLtvAtDecision(),
                snapshot.getAssetValueAtDecision(),
                snapshot.getRequestedAmountAtDecision(),
                snapshot.getRequestedTermAtDecision(),
                snapshot.getApprovedAmount(),
                List.copyOf(snapshot.getReasonCodes()),
                snapshot.getDecidedAt());
    }
}

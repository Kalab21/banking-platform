package com.bankingplatform.transaction.controller;

import com.bankingplatform.transaction.model.TransferAttempt;
import com.bankingplatform.transaction.service.TransferReconciler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * What a person needs to see when a transfer did not finish.
 *
 * <p>On {@code /internal/**} for the same reason as the balance endpoint: it
 * is reachable only from inside the Compose network, nothing routes it at the
 * gateway, and it takes no caller identity. It reports on other customers'
 * money, so it is not something a customer may call.
 *
 * <p>Read and re-check only. Nothing here moves money: what to do about a
 * half-applied transfer is a decision about whose account is made whole, and
 * the platform does not make it automatically.
 */
@RestController
@RequestMapping("/internal/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfer reconciliation (internal)")
public class InternalReconciliationController {

    private final TransferReconciler reconciler;

    @GetMapping("/unsettled")
    @Operation(summary = "Transfers that did not finish and may need a decision")
    public ResponseEntity<List<TransferAttempt>> unsettled() {
        return ResponseEntity.ok(reconciler.unsettled());
    }

    /**
     * Runs a reconciliation pass now instead of waiting for the timer.
     *
     * <p>Establishes the truth and records it. It still does not repair
     * anything.
     */
    @PostMapping("/reconcile")
    @Operation(summary = "Ask account-service what happened to each unsettled transfer")
    public ResponseEntity<Map<String, Integer>> reconcile() {
        return ResponseEntity.ok(Map.of("reconciled", reconciler.reconcile()));
    }
}

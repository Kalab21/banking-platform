package com.bankingplatform.application.controller;

import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.application.dto.ApplicationResponse;
import com.bankingplatform.application.dto.CreateApplicationRequest;
import com.bankingplatform.application.dto.DecisionSnapshotResponse;
import com.bankingplatform.application.dto.OfferResponse;
import com.bankingplatform.application.dto.ReviewRequest;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.service.ApplicationService;
import com.bankingplatform.application.service.OfferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Product applications — accounts, loans and cards — reached through the API
 * gateway.
 *
 * <p>An application carries what a customer asked for and what the bank decided,
 * including the credit score captured at the time. A customer may submit and
 * read their own and cancel one that has not completed; the queue by status and
 * the manual decision are staff work.
 *
 * <p>None of that was enforced. The user id arrived in a path, a body or a
 * query string and was used directly, so any authenticated customer could read
 * another's applications, work the staff queue, and approve their own request.
 */
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
@Tag(name = "Applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final OfferService offerService;

    /**
     * A reviewer's notes are the bank's working, not the customer's record.
     *
     * The offer response already leaves them out for that reason, but the
     * application itself carried them to its owner, so a note written for
     * colleagues was readable by the person it was about. Staff still see them.
     */
    private static ApplicationResponse forCaller(ApplicationResponse application, CallerIdentity caller) {
        if (!caller.isStaff()) application.setReviewerNotes(null);
        return application;
    }

    @PostMapping
    @Operation(summary = "Submit a new product application")
    public ResponseEntity<ApplicationResponse> submit(@Valid @RequestBody CreateApplicationRequest request,
                                                       CallerIdentity caller) {
        AccessGuard.requireTargetUserAllowed(caller, request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(forCaller(applicationService.submitApplication(request), caller));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get application by ID")
    public ResponseEntity<ApplicationResponse> getById(@PathVariable Long id, CallerIdentity caller) {
        ApplicationResponse application = applicationService.getById(id);
        AccessGuard.requireOwnerOrStaff(caller, application.getUserId());
        return ResponseEntity.ok(forCaller(application, caller));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all applications for a user")
    public ResponseEntity<List<ApplicationResponse>> getByUser(@PathVariable Long userId,
                                                                CallerIdentity caller) {
        AccessGuard.requireTargetUserAllowed(caller, userId);
        return ResponseEntity.ok(applicationService.getByUserId(userId).stream()
                .map(application -> forCaller(application, caller))
                .toList());
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "Get applications by status (staff only)")
    public ResponseEntity<List<ApplicationResponse>> getByStatus(@PathVariable ApplicationStatus status,
                                                                  CallerIdentity caller) {
        // The whole queue, across every customer.
        AccessGuard.requireStaff(caller);
        return ResponseEntity.ok(applicationService.getByStatus(status));
    }

    @PutMapping("/{id}/review")
    @Operation(summary = "Manually approve or reject an application (staff only)")
    public ResponseEntity<ApplicationResponse> review(@PathVariable Long id,
                                                       @Valid @RequestBody ReviewRequest request,
                                                       CallerIdentity caller) {
        // Deciding an application is the bank's side of the transaction. Left
        // open, an applicant could approve their own loan and set the amount.
        AccessGuard.requireStaff(caller);
        return ResponseEntity.ok(applicationService.review(id, request));
    }

    @GetMapping("/{id}/decisions")
    @Operation(summary = "Every decision taken on an application (staff only)")
    public ResponseEntity<List<DecisionSnapshotResponse>> decisions(@PathVariable Long id,
                                                                    CallerIdentity caller) {
        // Staff-only, and not because the figures are secret from the customer
        // who supplied most of them. A decision record carries the policy
        // version, the exact ratios and the internal reason codes — the
        // material for reviewing the bank's own decision, which is a different
        // job from being told the outcome. The customer's view is the
        // application and its offer.
        AccessGuard.requireStaff(caller);
        return ResponseEntity.ok(applicationService.decisionsFor(id));
    }

    @GetMapping("/{id}/offers")
    @Operation(summary = "The offers made on an application")
    public ResponseEntity<List<OfferResponse>> offers(@PathVariable Long id, CallerIdentity caller) {
        ApplicationResponse application = applicationService.getById(id);
        AccessGuard.requireOwnerOrStaff(caller, application.getUserId());
        return ResponseEntity.ok(offerService.forApplication(id));
    }

    @PostMapping("/{id}/offer/accept")
    @Operation(summary = "Accept the offer as made")
    public ResponseEntity<OfferResponse> acceptOffer(@PathVariable Long id, CallerIdentity caller) {
        // There is no request body, and that is the point: a customer accepts
        // the offer that was made, not a version of it they have edited. The
        // terms come from the stored offer and nowhere else.
        //
        // Only the applicant answers an offer. Staff decide whether to make
        // one; accepting or declining it is the customer's decision, and
        // Northbank has no assisted-service workflow that would let an
        // employee express it for them.
        ApplicationResponse application = applicationService.getById(id);
        AccessGuard.requireSelf(caller, application.getUserId());
        return ResponseEntity.ok(offerService.accept(id, application.getUserId()));
    }

    @PostMapping("/{id}/offer/decline")
    @Operation(summary = "Decline the offer")
    public ResponseEntity<OfferResponse> declineOffer(@PathVariable Long id, CallerIdentity caller) {
        // The applicant's decision, as for acceptance.
        ApplicationResponse application = applicationService.getById(id);
        AccessGuard.requireSelf(caller, application.getUserId());
        return ResponseEntity.ok(offerService.decline(id, application.getUserId()));
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending application")
    public ResponseEntity<ApplicationResponse> cancel(@PathVariable Long id, CallerIdentity caller) {
        // The owner comes from the stored application. The service already
        // refused to cancel "another user's application", but it was comparing
        // against a userId the caller supplied in the query string, so naming
        // the victim's id satisfied the check.
        ApplicationResponse application = applicationService.getById(id);
        AccessGuard.requireOwnerOrStaff(caller, application.getUserId());

        return ResponseEntity.ok(forCaller(applicationService.cancel(id, application.getUserId()), caller));
    }
}

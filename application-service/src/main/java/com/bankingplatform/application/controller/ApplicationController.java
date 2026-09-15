package com.bankingplatform.application.controller;

import com.bankingplatform.application.dto.ApplicationResponse;
import com.bankingplatform.application.dto.CreateApplicationRequest;
import com.bankingplatform.application.dto.ReviewRequest;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.service.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
@Tag(name = "Applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    @PostMapping
    @Operation(summary = "Submit a new product application")
    public ResponseEntity<ApplicationResponse> submit(@Valid @RequestBody CreateApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(applicationService.submitApplication(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get application by ID")
    public ResponseEntity<ApplicationResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(applicationService.getById(id));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all applications for a user")
    public ResponseEntity<List<ApplicationResponse>> getByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(applicationService.getByUserId(userId));
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "Get applications by status (admin use)")
    public ResponseEntity<List<ApplicationResponse>> getByStatus(@PathVariable ApplicationStatus status) {
        return ResponseEntity.ok(applicationService.getByStatus(status));
    }

    @PutMapping("/{id}/review")
    @Operation(summary = "Manually approve or reject an application")
    public ResponseEntity<ApplicationResponse> review(@PathVariable Long id,
                                                       @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(applicationService.review(id, request));
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending application")
    public ResponseEntity<ApplicationResponse> cancel(@PathVariable Long id,
                                                       @RequestParam Long userId) {
        return ResponseEntity.ok(applicationService.cancel(id, userId));
    }
}

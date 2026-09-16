package com.bankingplatform.user.controller;

import com.bankingplatform.user.dto.UpdateUserRequest;
import com.bankingplatform.user.dto.UserResponse;
import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User profile operations.
 *
 * <p>Role checks here come from Spring Security, which user-service runs
 * because it issues the tokens. Ownership comes from the identity the gateway
 * derived. The two are complementary rather than competing: both are grounded
 * in the same JWT, and a role alone was never enough to decide whether a caller
 * may read or change a particular profile.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users")
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id, CallerIdentity caller) {
        AccessGuard.requireOwnerOrStaff(caller, id);
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping("/username/{username}")
    @Operation(summary = "Get user by username")
    public ResponseEntity<UserResponse> getUserByUsername(@PathVariable String username, CallerIdentity caller) {
        UserResponse user = userService.getUserByUsername(username);
        // Looked up first, then authorised against the owner: a username is
        // guessable, so it must not be a way around the id-based rule.
        AccessGuard.requireOwnerOrStaff(caller, user.getId());
        return ResponseEntity.ok(user);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user profile")
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long id,
                                                    @Valid @RequestBody UpdateUserRequest request,
                                                    CallerIdentity caller) {
        AccessGuard.requireOwnerOrStaff(caller, id);
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List all users (ADMIN only)")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }
}

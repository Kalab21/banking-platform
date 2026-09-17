package com.bankingplatform.notification.controller;

import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.notification.dto.NotificationResponse;
import com.bankingplatform.notification.dto.PagedNotificationsResponse;
import com.bankingplatform.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * A customer's notifications, reached through the API gateway.
 *
 * <p>Notifications describe a customer's own activity — money moved, an
 * application decided, a card issued — so they are readable only by that
 * customer or by staff. The user id in the path used to be taken as the
 * authority for the read rather than as the identifier of whose list was
 * wanted, which made every customer's alerts readable by any other.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get paginated notifications for a user")
    public ResponseEntity<PagedNotificationsResponse> getNotifications(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            CallerIdentity caller) {
        AccessGuard.requireTargetUserAllowed(caller, userId);
        return ResponseEntity.ok(notificationService.getNotifications(userId, page, size));
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "Mark a notification as read")
    public ResponseEntity<NotificationResponse> markAsRead(@PathVariable Long id,
                                                            CallerIdentity caller) {
        // The owner is the caller. This used to take a userId query parameter
        // and scope the update by it, so supplying someone else's id marked
        // their notification read.
        //
        // The service scopes the update by both id and user, so a notification
        // belonging to anyone else is simply not found rather than modified.
        return ResponseEntity.ok(notificationService.markAsRead(id, caller.userId()));
    }

    @PutMapping("/user/{userId}/read-all")
    @Operation(summary = "Mark all notifications as read for a user")
    public ResponseEntity<Void> markAllAsRead(@PathVariable Long userId, CallerIdentity caller) {
        AccessGuard.requireTargetUserAllowed(caller, userId);
        notificationService.markAllAsRead(userId);
        return ResponseEntity.noContent().build();
    }
}

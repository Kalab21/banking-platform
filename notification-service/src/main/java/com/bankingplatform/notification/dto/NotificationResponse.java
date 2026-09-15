package com.bankingplatform.notification.dto;

import com.bankingplatform.notification.model.NotificationType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data @Builder
public class NotificationResponse {
    private Long id;
    private Long userId;
    private NotificationType type;
    private String title;
    private String message;
    private boolean isRead;
    private String referenceId;
    private String referenceType;
    private LocalDateTime createdAt;
}

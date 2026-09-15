package com.bankingplatform.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data @Builder
public class PagedNotificationsResponse {
    private List<NotificationResponse> notifications;
    private long unreadCount;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}

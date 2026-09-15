package com.bankingplatform.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreditScoreHistoryResponse {

    private Long id;
    private Long userId;
    private int oldScore;
    private int newScore;
    private int delta;
    private String changeReason;
    private LocalDateTime createdAt;
}

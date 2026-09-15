package com.bankingplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class CreditScoreResponse {

    private Long userId;
    private int score;
    private String rating;
    private LocalDateTime updatedAt;

    public static String toRating(int score) {
        if (score >= 800) return "EXCEPTIONAL";
        if (score >= 740) return "VERY_GOOD";
        if (score >= 670) return "GOOD";
        if (score >= 580) return "FAIR";
        return "POOR";
    }
}

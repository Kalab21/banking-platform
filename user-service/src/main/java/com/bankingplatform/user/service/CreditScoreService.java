package com.bankingplatform.user.service;

import com.bankingplatform.user.dto.CreditScoreHistoryResponse;
import com.bankingplatform.user.dto.CreditScoreResponse;

import java.util.List;

public interface CreditScoreService {

    CreditScoreResponse getScore(Long userId);

    CreditScoreResponse updateScore(Long userId, int delta, String reason);

    List<CreditScoreHistoryResponse> getHistory(Long userId);
}

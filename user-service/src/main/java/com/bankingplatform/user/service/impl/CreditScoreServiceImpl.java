package com.bankingplatform.user.service.impl;

import com.bankingplatform.common.observability.LogSafe;
import com.bankingplatform.user.dto.CreditScoreHistoryResponse;
import com.bankingplatform.user.dto.CreditScoreResponse;
import com.bankingplatform.user.exception.ResourceNotFoundException;
import com.bankingplatform.user.model.CreditScoreHistory;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.CreditScoreHistoryRepository;
import com.bankingplatform.user.repository.UserRepository;
import com.bankingplatform.user.service.CreditScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditScoreServiceImpl implements CreditScoreService {

    private static final int MIN_SCORE = 300;
    private static final int MAX_SCORE = 850;

    private final UserRepository userRepository;
    private final CreditScoreHistoryRepository creditScoreHistoryRepository;

    @Override
    public CreditScoreResponse getScore(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return new CreditScoreResponse(
                userId,
                user.getCreditScore(),
                CreditScoreResponse.toRating(user.getCreditScore()),
                user.getCreditScoreUpdatedAt()
        );
    }

    @Override
    @Transactional
    public CreditScoreResponse updateScore(Long userId, int delta, String reason) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        int oldScore = user.getCreditScore();
        int newScore = Math.max(MIN_SCORE, Math.min(MAX_SCORE, oldScore + delta));

        user.setCreditScore(newScore);
        user.setCreditScoreUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        CreditScoreHistory history = new CreditScoreHistory();
        history.setUserId(userId);
        history.setOldScore(oldScore);
        history.setNewScore(newScore);
        history.setChangeReason(reason);
        creditScoreHistoryRepository.save(history);

        // The reason is caller-supplied free text. It is stored verbatim —
        // that is the audit record — and neutralised only here, where it would
        // otherwise be able to end this line and start a fabricated one.
        log.info("Credit score updated: userId={}, {} → {} ({})", userId, oldScore, newScore,
                LogSafe.value(reason));
        return new CreditScoreResponse(
                userId,
                newScore,
                CreditScoreResponse.toRating(newScore),
                user.getCreditScoreUpdatedAt()
        );
    }

    @Override
    public List<CreditScoreHistoryResponse> getHistory(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
        return creditScoreHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(h -> {
                    CreditScoreHistoryResponse r = new CreditScoreHistoryResponse();
                    r.setId(h.getId());
                    r.setUserId(h.getUserId());
                    r.setOldScore(h.getOldScore());
                    r.setNewScore(h.getNewScore());
                    r.setDelta(h.getNewScore() - h.getOldScore());
                    r.setChangeReason(h.getChangeReason());
                    r.setCreatedAt(h.getCreatedAt());
                    return r;
                })
                .collect(Collectors.toList());
    }
}

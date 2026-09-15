package com.bankingplatform.user.repository;

import com.bankingplatform.user.model.CreditScoreHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditScoreHistoryRepository extends JpaRepository<CreditScoreHistory, Long> {
    List<CreditScoreHistory> findByUserIdOrderByCreatedAtDesc(Long userId);
}

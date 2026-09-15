package com.bankingplatform.creditcard.repository;

import com.bankingplatform.creditcard.model.CreditCardTransaction;
import com.bankingplatform.creditcard.model.CreditCardTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface CreditCardTransactionRepository extends JpaRepository<CreditCardTransaction, Long> {
    Page<CreditCardTransaction> findByCreditCardIdOrderByCreatedAtDesc(Long cardId, Pageable pageable);
    Optional<CreditCardTransaction> findByTransactionRef(String transactionRef);
    boolean existsByTransactionRef(String transactionRef);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM CreditCardTransaction t " +
           "WHERE t.creditCard.id = :cardId AND t.type = :type " +
           "AND t.createdAt BETWEEN :from AND :to")
    BigDecimal sumByCardIdAndTypeBetween(Long cardId, CreditCardTransactionType type,
                                         LocalDateTime from, LocalDateTime to);
}

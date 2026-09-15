package com.bankingplatform.creditcard.repository;

import com.bankingplatform.creditcard.model.CreditCardStatement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CreditCardStatementRepository extends JpaRepository<CreditCardStatement, Long> {
    List<CreditCardStatement> findByCreditCardIdOrderByStatementDateDesc(Long cardId);
    Optional<CreditCardStatement> findByCreditCardIdAndStatementDate(Long cardId, LocalDate statementDate);
    boolean existsByCreditCardIdAndStatementDate(Long cardId, LocalDate statementDate);
}

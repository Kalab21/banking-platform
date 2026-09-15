package com.bankingplatform.creditcard.repository;

import com.bankingplatform.creditcard.model.CreditCard;
import com.bankingplatform.creditcard.model.CardStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditCardRepository extends JpaRepository<CreditCard, Long> {
    List<CreditCard> findByUserId(Long userId);
    Optional<CreditCard> findByCardNumber(String cardNumber);
    List<CreditCard> findByStatus(CardStatus status);
    boolean existsByCardNumber(String cardNumber);
}

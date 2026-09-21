package com.bankingplatform.creditcard.repository;

import com.bankingplatform.creditcard.model.CreditCard;
import com.bankingplatform.creditcard.model.CardStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CreditCardRepository extends JpaRepository<CreditCard, Long> {
    List<CreditCard> findByUserId(Long userId);
    Optional<CreditCard> findByCardNumber(String cardNumber);
    List<CreditCard> findByStatus(CardStatus status);
    boolean existsByCardNumber(String cardNumber);

    /**
     * The card, held for the rest of the transaction.
     *
     * <p>Every money path on a card is a read, a check against the credit
     * limit, and a write derived from what was read. Without the lock those
     * three steps are not one act: two purchases arriving together both read
     * the same available credit, both pass the check, and both write, leaving
     * the card over its limit by the smaller amount with nothing recording
     * that it happened.
     *
     * <p>Pessimistic rather than optimistic on purpose. Optimistic locking
     * detects the collision after the fact and asks the caller to retry, which
     * for a card authorisation means either failing a legitimate purchase or
     * replaying one — and replaying money movement is what the idempotency key
     * exists to prevent. Serialising the contended card is the cheaper answer,
     * and contention on a single card is rare by nature.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CreditCard c WHERE c.id = :id")
    Optional<CreditCard> findByIdForUpdate(@Param("id") Long id);
}

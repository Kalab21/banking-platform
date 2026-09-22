package com.bankingplatform.application.repository;

import com.bankingplatform.application.model.Offer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OfferRepository extends JpaRepository<Offer, Long> {

    /** The live offer on an application, if it has one. */
    Optional<Offer> findByApplicationIdAndStatus(Long applicationId, Offer.Status status);

    List<Offer> findByApplicationIdOrderByCreatedAtDesc(Long applicationId);

    List<Offer> findByUserIdOrderByCreatedAtDesc(Long userId);
}

package com.bankingplatform.integration.repository;

import com.bankingplatform.integration.model.ExternalTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExternalTransferRepository extends JpaRepository<ExternalTransfer, Long> {
    Optional<ExternalTransfer> findByTransferRef(String transferRef);
}

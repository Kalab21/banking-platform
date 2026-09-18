package com.bankingplatform.user.repository;

import com.bankingplatform.user.model.CustomerIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerIdentityRepository extends JpaRepository<CustomerIdentity, Long> {

    Optional<CustomerIdentity> findByUserId(Long userId);
}

package com.poc.backend.payment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentSessionRepository extends JpaRepository<PaymentSession, String> {

  Optional<PaymentSession> findFirstByProcessInstanceIdAndStatusOrderByCreatedAtDesc(
      String processInstanceId, String status);
}

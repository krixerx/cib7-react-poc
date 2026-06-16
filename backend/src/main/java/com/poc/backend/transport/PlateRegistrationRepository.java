package com.poc.backend.transport;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlateRegistrationRepository extends JpaRepository<PlateRegistration, Long> {

  boolean existsByPlateNumber(String plateNumber);
}

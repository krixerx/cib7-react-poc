package com.poc.backend.rop;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlateRegistrationRepository extends JpaRepository<PlateRegistration, Long> {

  boolean existsByPlateNumber(String plateNumber);
}

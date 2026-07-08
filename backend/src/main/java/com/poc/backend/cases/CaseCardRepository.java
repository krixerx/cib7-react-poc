package com.poc.backend.cases;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseCardRepository extends JpaRepository<CaseCard, String> {

  List<CaseCard> findAllByOrderByUpdatedAtDesc();
}

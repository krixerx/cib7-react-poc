package com.poc.backend.documents;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, String> {

  List<Document> findByProcessInstanceIdOrderByCreatedAtAsc(String processInstanceId);
}

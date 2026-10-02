package com.poc.backend.documents;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, String> {

  List<Document> findByProcessInstanceIdOrderByCreatedAtAsc(String processInstanceId);

  List<Document> findByProcessInstanceIdInOrderByCreatedAtDesc(
      Collection<String> processInstanceIds);
}

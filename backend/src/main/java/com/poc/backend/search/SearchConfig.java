package com.poc.backend.search;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Vector index for semantic document search — the RAG surface behind the MCP {@code
 * search_documents} tool.
 *
 * <p>{@link SimpleVectorStore} is in-memory brute-force cosine, which is deliberate: this module
 * runs on in-memory H2, so the index resets together with the {@link
 * com.poc.backend.documents.Document} rows (and the process instances) it points at, and the corpus
 * is a handful of small documents per case — far below where an ANN index pays for itself. When the
 * stack graduates to Postgres, swap this bean for {@code PgVectorStore}; {@link DocumentIndexer}
 * and {@link DocumentSearchController} only see the {@code VectorStore} interface.
 *
 * <p>{@code @EnableAsync} is for {@link DocumentIndexer}: extraction + embedding runs off the
 * request thread so document registration latency is untouched.
 */
@Configuration
@EnableAsync
public class SearchConfig {

  @Bean
  public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
  }
}

package com.poc.cib7.db;

import java.util.Set;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.spring.ProcessEngineFactoryBean;
import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDependsOnDatabaseInitializationDetector;

/**
 * Makes the process engine wait for Flyway. Spring Boot orders JPA and JDBC template beans after
 * database initialization by itself, but knows nothing about the CIB seven engine, which reads its
 * schema version while it builds. Without this, the engine could start before {@link
 * V1__CibSevenSchema} ran and fail on missing tables. Registered in {@code
 * META-INF/spring.factories}.
 */
public class ProcessEngineAfterFlywayDetector
    extends AbstractBeansOfTypeDependsOnDatabaseInitializationDetector {

  @Override
  protected Set<Class<?>> getDependsOnDatabaseInitializationBeanTypes() {
    return Set.of(ProcessEngine.class, ProcessEngineFactoryBean.class);
  }
}

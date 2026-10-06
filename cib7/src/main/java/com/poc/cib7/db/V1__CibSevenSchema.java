package com.poc.cib7.db;

import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * Creates the CIB seven 2.2 schema by running the create scripts the engine jar ships, so Flyway
 * owns the {@code ACT_*} tables instead of the engine.
 *
 * <p>Why not {@code camunda.bpm.database.schema-update: true}: it only creates missing tables and
 * never applies the jar's {@code db/upgrade} scripts, so after a CIB seven upgrade on a persistent
 * database the engine would refuse to start on a version mismatch. With Flyway, an engine upgrade
 * ships as the next migration that runs the matching {@code <db>_engine_<from>_to_<to>.sql} from
 * the new jar, and the engine itself only checks the version ({@code schema-update: false}).
 *
 * <p>The component order is the engine's own ({@code AbstractPersistenceSession#dbSchemaUpdate});
 * every component is on in this deployment's defaults. The database type picks the script variant,
 * so tests on H2 run the same migration as Postgres.
 */
@Component
public class V1__CibSevenSchema extends BaseJavaMigration {

  private static final List<String> COMPONENTS =
      List.of(
          "engine",
          "history",
          "identity",
          "case.engine",
          "case.history",
          "decision.engine",
          "decision.history",
          "modeler");

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    String db = databaseType(connection.getMetaData().getDatabaseProductName());
    for (String component : COMPONENTS) {
      ScriptUtils.executeSqlScript(
          connection,
          new ClassPathResource(
              "org/cibseven/bpm/engine/db/create/activiti."
                  + db
                  + ".create."
                  + component
                  + ".sql"));
    }
  }

  /** CIB seven's script name for a JDBC product name. */
  static String databaseType(String productName) {
    String name = productName.toLowerCase(Locale.ROOT);
    if (name.contains("postgres")) {
      return "postgres";
    }
    if (name.equals("h2")) {
      return "h2";
    }
    throw new IllegalStateException("No CIB seven schema scripts wired for " + productName);
  }
}

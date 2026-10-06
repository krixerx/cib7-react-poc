# Build context is the repo root (see docker-compose.yml): the image carries the
# reference service pack from packs/reference/engine, and the pom reaches it as
# ../packs/reference/engine. cib7/Dockerfile.dockerignore limits the context to
# cib7/ and packs/.

# --- build stage ---------------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build/cib7
COPY cib7/ .
COPY packs/ /build/packs/
RUN mvn -B -DskipTests clean package

# --- runtime stage -------------------------------------------------------
# Pinned to a Temurin patch release; Dependabot's docker ecosystem proposes
# the next one.
FROM eclipse-temurin:21.0.12.1_1-jre
# Runs as an unprivileged system user (docs/security.md rule 11). The jar is
# root-owned and read-only to it; /app is its own so anything the app writes
# next to the jar does not need root.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/cib7/target/*.jar app.jar
# The service pack: BPMN, DMN, variable policies and FreeMarker templates. The
# jar holds none of them. Root-owned and read-only to the app user; a customer
# pack replaces this directory (a bind mount or a thin image layer).
COPY packs/reference/engine/ /opt/services/
RUN chown app:app /app
USER 10001:10001
EXPOSE 8080
# PropertiesLauncher instead of `java -jar` so loader.path can put the pack
# directory on the classpath: ServiceDeployments, VariablePolicyRegistry and the
# BPMN `resource="templates/..."` script references all resolve from there.
ENTRYPOINT ["java", "-Dloader.path=/opt/services", "-cp", "app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

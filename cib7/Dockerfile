# The core engine image: CIB seven, the core plugins and nothing of a service
# pack. /opt/services, on the classpath through loader.path, is empty here; a
# pack's image is a thin layer FROM this one that adds its engine files,
# branding and pack.yaml there (packs/reference/docker/engine.Dockerfile), so
# a core update reaches a customer as a base image bump.
#
# Build context is the repo root (see docker-compose.yml);
# cib7/Dockerfile.dockerignore limits it to cib7/. The pom's pack directory
# is a test resource only, and the tests are skipped here.

# --- build stage ---------------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build/cib7
COPY cib7/ .
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
# Where a pack's layer puts its files (root-owned, read-only to the app user).
RUN mkdir /opt/services && chown app:app /app
USER 10001:10001
EXPOSE 8080
# PropertiesLauncher instead of `java -jar` so loader.path can put the pack
# directory on the classpath: ServiceDeployments, VariablePolicyRegistry and the
# BPMN `resource="templates/..."` script references all resolve from there.
ENTRYPOINT ["java", "-Dloader.path=/opt/services", "-cp", "app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

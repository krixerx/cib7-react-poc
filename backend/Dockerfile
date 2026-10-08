# The core backend image: the business microservice and nothing of a service
# pack. /opt/services, on the classpath through loader.path, is empty here; a
# pack's image is a thin layer FROM this one that adds its backend files and
# pack.yaml there (a pack's docker/backend.Dockerfile).
#
# Build context is the repo root (see docker-compose.yml);
# backend/Dockerfile.dockerignore limits it to backend/. The pom's pack
# directory is a test resource only, and the tests are skipped here.

# --- build stage ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /build/backend
COPY backend/ .
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
COPY --from=build /build/backend/target/*.jar app.jar
# Where a pack's layer puts its files (root-owned, read-only to the app user).
RUN mkdir /opt/services && chown app:app /app
USER 10001:10001
EXPOSE 8085
# PropertiesLauncher instead of `java -jar` so loader.path can put the pack
# directory on the classpath: RegistryCatalog reads registry/*.yaml and
# RegistryMigrations runs db/registry from there.
ENTRYPOINT ["java", "-Dloader.path=/opt/services", "-cp", "app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

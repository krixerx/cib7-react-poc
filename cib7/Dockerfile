# --- build stage ---------------------------------------------------------
FROM maven:3-eclipse-temurin-24 AS build
WORKDIR /build
COPY . .
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
COPY --from=build /build/target/*.jar app.jar
RUN chown app:app /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# Built from the repository root (see docker-compose.yml) so the build can see
# both the cib7 engine module and the project-local lib/ Maven repository.
#
# --- build stage ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY lib lib
COPY cib7 cib7
RUN mvn -B -DskipTests -f cib7/pom.xml clean package

# --- runtime stage -------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/cib7/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

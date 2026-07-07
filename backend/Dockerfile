# --- build stage ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -DskipTests clean package

# --- runtime stage -------------------------------------------------------
# Document-search embeddings come from the separate TEI container
# (`embeddings` in compose) over its OpenAI-compatible API — no model files
# in this image; EMBEDDINGS_URL points the backend at it.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]

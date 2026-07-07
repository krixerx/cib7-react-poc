# --- build stage ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -DskipTests clean package

# --- embedding model stage ------------------------------------------------
# The document-search index embeds with all-MiniLM-L6-v2 running in-process
# (ONNX). Bake the model files into the image so container startup never
# dials HuggingFace — application.yaml flips to these file: URIs via the
# EMBEDDING_* env vars below.
FROM curlimages/curl:8.11.1 AS models
RUN curl -fsSL --create-dirs \
      -o /tmp/models/model.onnx \
      https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx && \
    curl -fsSL \
      -o /tmp/models/tokenizer.json \
      https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json

# --- runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
COPY --from=models /tmp/models /app/models
ENV EMBEDDING_ONNX_MODEL_URI=file:/app/models/model.onnx \
    EMBEDDING_TOKENIZER_URI=file:/app/models/tokenizer.json
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]

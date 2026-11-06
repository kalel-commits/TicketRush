# Build context: repository root.  docker build -f ai-assistant/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q -pl ai-assistant -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/ai-assistant/target/ai-assistant-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Duser.timezone=UTC"
# CPU libtorch only (DJL would fetch multi-GB CUDA builds on GPU hosts); models cached in the assistant_models volume
ENV PYTORCH_FLAVOR=cpu DJL_CACHE_DIR=/models/djl ONNX_CACHE_DIR=/models/onnx
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

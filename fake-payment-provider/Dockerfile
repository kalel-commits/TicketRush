# Build context: repository root.  docker build -f fake-payment-provider/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q -pl fake-payment-provider -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/fake-payment-provider/target/fake-payment-provider-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx128m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Duser.timezone=UTC"
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

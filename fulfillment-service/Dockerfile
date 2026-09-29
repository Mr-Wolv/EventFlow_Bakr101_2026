# Build stage uses the multi-module root pom so the parent + sibling module poms resolve.
# Dependencies are downloaded in a separate layer so source changes don't re-download.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY order-service/pom.xml order-service/pom.xml
COPY fulfillment-service/pom.xml fulfillment-service/pom.xml
RUN mvn -B -q dependency:go-offline
COPY fulfillment-service/src fulfillment-service/src
RUN mvn -B -q -f fulfillment-service/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/fulfillment-service/target/fulfillment-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

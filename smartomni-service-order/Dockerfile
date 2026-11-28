FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp -pl smartomni-service-order -am -DskipTests clean package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/smartomni-service-order/target/*-exec.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]

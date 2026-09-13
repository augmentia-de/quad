FROM eclipse-temurin:21-jre AS builder

WORKDIR /app
COPY quad ./quad
WORKDIR /app/quad
RUN ./mvnw dependency:go-offline -B -q

WORKDIR /app/quad/quad-quarkus
RUN ./mvnw package -DskipTests -B -q

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /app/quad/quad-quarkus/target/quad-quarkus-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
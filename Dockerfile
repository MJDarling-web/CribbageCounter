# Build stage
FROM maven:3.9.8-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

# Runtime stage
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /app/target/cribbage-counter.jar /app/cribbage-counter.jar

ENV PORT=7070
ENV DATA_FILE=/var/data/local-store.ser

EXPOSE 7070
CMD ["java", "-jar", "/app/cribbage-counter.jar"]


FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssh-client && rm -rf /var/lib/apt/lists/* && groupadd --gid 10001 infrapilot && useradd --uid 10001 --gid infrapilot --no-create-home infrapilot && mkdir /app/data && chown infrapilot:infrapilot /app/data
COPY --from=build /app/target/infrapilot-0.1.0.jar /app/infrapilot.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/infrapilot.jar"]

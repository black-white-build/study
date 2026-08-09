FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM amazoncorretto:21-alpine
WORKDIR /app
RUN addgroup -S heartpilot && adduser -S heartpilot -G heartpilot && mkdir -p /app/data/files && chown -R heartpilot:heartpilot /app
COPY --from=build /workspace/target/heart-pilot-backend-0.0.1-SNAPSHOT.jar app.jar
USER heartpilot
EXPOSE 8123
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar","--spring.profiles.active=prod"]

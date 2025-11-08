# Build stage: compile and produce shaded jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Runtime stage: slim JRE only
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/QueueCTL-1.0-SNAPSHOT-shaded.jar /app/queuectl.jar

# Default data dir under /data, map to host via -v $(pwd)/data:/data
ENV QUEUECTL_HOME=/data
VOLUME ["/data"]

# Expose web UI port if you choose to run the web server (optional)
EXPOSE 8080

# Entrypoint: pass CLI args, allow overriding user.home to map to /data
ENTRYPOINT ["java", "-Duser.home=/data", "-jar", "/app/queuectl.jar"]
CMD ["status"]

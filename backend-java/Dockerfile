# =============================================================================
# Intelligent Resume Screening System - Java Spring Boot Backend
# Multi-stage Dockerfile
# =============================================================================

# Stage 1: Build stage with Maven and Eclipse Temurin JDK 17
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /workspace

# Copy POM and download dependencies for efficient layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy application source code and compile production JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Production runtime stage with minimal Eclipse Temurin JRE 17
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install curl for container health check
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Create unprivileged system group and user for secure non-root execution
RUN groupadd -r appgroup && useradd -r -g appgroup -s /sbin/nologin -d /app appuser

# Set up persistent upload and log directories with non-root ownership
RUN mkdir -p /app/uploads/resumes /app/uploads/batches && \
    chown -R appuser:appgroup /app

# Copy the built application JAR from builder stage
COPY --from=builder --chown=appuser:appgroup /workspace/target/backend-java-*.jar /app/app.jar

USER appuser

EXPOSE 8080

# Health check using the database-aware /health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]


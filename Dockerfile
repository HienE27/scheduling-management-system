# Hospital Scheduler - Backend Dockerfile (built from repo root)
# Build context: repo root; sources are copied from backend/ subdir

FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copy Maven build files first for better layer caching
COPY backend/pom.xml ./
COPY backend/mvnw ./
COPY backend/.mvn ./.mvn

RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code
COPY backend/src ./src

# Build the application (skip tests; CI handles test execution)
RUN ./mvnw package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# Copy the built JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose Spring Boot port
EXPOSE 8080

# Health check using actuator
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application with bounded JVM heap (Render free tier has 512MB)
ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "app.jar"]

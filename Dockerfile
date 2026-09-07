# Stage 1: Build Angular Frontend
FROM node:24-slim AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm install
COPY frontend/ ./
RUN npm run build

# Stage 2: Build Spring Boot Backend
FROM eclipse-temurin:21-jdk AS backend-builder
WORKDIR /app/backend
COPY backend/ ./
# Copy Angular production static assets into Spring Boot static resources
COPY --from=frontend-builder /app/frontend/dist/frontend/browser/ src/main/resources/static/
RUN ./gradlew bootJar --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/db
COPY --from=backend-builder /app/backend/build/libs/finally-backend-*.jar app.jar

ENV SERVER_PORT=8000
ENV SPRING_DATASOURCE_URL=jdbc:sqlite:/app/db/finally.db

EXPOSE 8000
VOLUME ["/app/db"]

CMD ["java", "-Dspring.datasource.url=${SPRING_DATASOURCE_URL}", "-jar", "app.jar"]

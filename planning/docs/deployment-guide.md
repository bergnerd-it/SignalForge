# FinAlly Deployment Guide

## 1. Architectural Deployment Overview

FinAlly is engineered as a **unified single-container, single-port application** (port `8000` by default). In production builds:
1. The **Angular frontend** is compiled into static HTML/JS/CSS bundles via `ng build`.
2. Static assets are packaged directly into the **Spring Boot backend** (`src/main/resources/static/`).
3. Spring Boot's embedded Tomcat web server serves the Angular SPA static resources, REST endpoints (`/api/*`), and real-time Server-Sent Events (`/api/stream/*`) from a single origin, eliminating CORS overhead.
4. Embedded **SQLite** database stores all data in a single file mounted to `/app/db/finally.db`.

```
┌─────────────────────────────────────────────────────────────┐
│                    Client Browser (Port 8000)               │
└──────────────────────────────▲──────────────────────────────┘
                               │ HTTP / SSE
┌──────────────────────────────▼──────────────────────────────┐
│                  Docker Container / Host                    │
│                                                             │
│  Spring Boot Embedded Server (Port 8000)                    │
│  ├── /*               -> Angular Production SPA             │
│  ├── /api/*           -> Spring Boot REST Endpoints         │
│  └── /api/stream/*    -> Real-time SSE Price Stream         │
│                                                             │
│  Mounted Persistence Volume:                                │
│  └── /app/db/finally.db (SQLite Database File)              │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Local Deployment Without Docker

### Prerequisites
- **Java**: JDK 21 or higher (`java -version`)
- **Node.js**: Node.js v24+ and npm (`node -v`, `npm -v`)

---

### Option A: Local Development Mode (Hot Reloading)
For active development, run frontend and backend concurrently with hot reloading.

#### 1. Backend Setup
```bash
# Navigate to backend directory
cd backend

# Configure environment variables (optional: create .env in project root or export vars)
export LLM_PROVIDER=openai
export LLM_API_KEY=your_api_key_here
# export LLM_MOCK=true  # Use deterministic mock mode if no API key

# Run Spring Boot backend on port 8000
./gradlew bootRun
```

#### 2. Frontend Setup
In a separate terminal:
```bash
# Navigate to frontend directory
cd frontend

# Install dependencies
npm install

# Start Angular development server on port 4200
npm start
```
*Note: The Angular development server is configured to proxy API requests to `http://localhost:8000`.*
- Open your browser at `http://localhost:4200`.

---

### Option B: Production Fat-JAR Mode (Single Process)
Package the entire application into a single executable JAR.

#### 1. Build Angular Static Assets
```bash
cd frontend
npm install
npm run build
```

#### 2. Copy Frontend Build into Spring Boot Static Folder
```bash
# From repository root
cp -r frontend/dist/frontend/browser/* backend/src/main/resources/static/
```

#### 3. Build & Run the Executable Spring Boot JAR
```bash
cd backend
./gradlew bootJar

# Run the fat JAR
java -Dspring.datasource.url=jdbc:sqlite:./finally.db \
     -DLLM_PROVIDER=openai \
     -DLLM_API_KEY=your_api_key_here \
     -jar build/libs/finally-backend-0.0.1-SNAPSHOT.jar
```
- Open your browser at `http://localhost:8000`.

---

## 3. Local Deployment With Docker

Docker provides the easiest one-command deployment, bundling the frontend build, backend build, and runtime environment into an optimized container.

### 1. Multi-Stage `Dockerfile` Walkthrough

The project uses a 3-stage `Dockerfile`:
- **Stage 1 (`frontend-builder`)**: Uses `node:24-slim` to install dependencies and execute `npm run build`.
- **Stage 2 (`backend-builder`)**: Uses `eclipse-temurin:21-jdk`, copies the Angular assets into `src/main/resources/static/`, and runs `./gradlew bootJar`.
- **Stage 3 (`Runtime`)**: Uses a lightweight `eclipse-temurin:21-jre` image, copies the fat JAR, sets up the SQLite volume directory `/app/db`, and exposes port `8000`.

```dockerfile
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
```

---

### 2. Running via Docker Compose

#### Step 1: Create `.env` file
Copy `.env.example` to `.env` in the repository root and configure your credentials:
```bash
cp .env.example .env
```
Edit `.env`:
```dotenv
LLM_PROVIDER=openai
LLM_API_KEY=your-api-key-here
LLM_MODEL=gpt-4o-mini
LLM_MOCK=false
MASSIVE_API_KEY=
```

#### Step 2: Build and Start Container
```bash
docker compose up -d --build
```

#### Step 3: View Logs and Status
```bash
# Stream container logs
docker compose logs -f

# Check container status
docker compose ps
```

#### Step 4: Stop Container
```bash
docker compose down
```
*Note: The SQLite database is safely persisted in the named Docker volume `finally-data`.*

---

### 3. Using Convenience Launch Scripts

Convenience scripts are provided in `scripts/`:

- **macOS / Linux**:
  ```bash
  ./scripts/start_mac.sh --build
  # To stop:
  ./scripts/stop_mac.sh
  ```
- **Windows (PowerShell)**:
  ```powershell
  .\scripts\start_windows.ps1 -Build
  # To stop:
  .\scripts\stop_windows.ps1
  ```

---

## 4. Remote Deployment With Docker (Production / VPS)

Deploying FinAlly to a cloud virtual machine (e.g., AWS EC2, DigitalOcean Droplet, Hetzner, GCP Compute Engine).

### Step 1: Build & Push Docker Image

Build and push the multi-arch image to a container registry (e.g., Docker Hub, GitHub Container Registry):

```bash
# Log in to registry
docker login ghcr.io -u <YOUR_GITHUB_USERNAME>

# Build and tag image
docker build -t ghcr.io/<your-org>/finally:latest .

# Push to registry
docker push ghcr.io/<your-org>/finally:latest
```

---

### Step 2: Deploy to Remote Server

On the remote Linux server:

#### 1. Create Deployment Directory
```bash
mkdir -p /opt/finally/db
cd /opt/finally
```

#### 2. Create Production `docker-compose.yml`
```yaml
services:
  finally:
    image: ghcr.io/<your-org>/finally:latest
    container_name: finally-prod
    restart: unless-stopped
    ports:
      - "127.0.0.1:8000:8000"
    environment:
      - LLM_PROVIDER=openai
      - LLM_API_KEY=${LLM_API_KEY}
      - LLM_MODEL=gpt-4o-mini
      - LLM_MOCK=false
      - SPRING_DATASOURCE_URL=jdbc:sqlite:/app/db/finally.db
    volumes:
      - /opt/finally/db:/app/db
```

#### 3. Start the Production Service
```bash
# Export secrets or load from secure .env
export LLM_API_KEY="sk-or-v1-..."
docker compose pull
docker compose up -d
```

---

### Step 3: Production Reverse Proxy & SSL Configuration (Nginx)

For production deployments, terminate SSL/TLS with Nginx or Caddy and proxy traffic to port 8000.

> **CRITICAL FOR SSE (Server-Sent Events)**: You **must disable proxy buffering** (`proxy_buffering off;`) and pass appropriate streaming headers so price ticks are delivered instantaneously to clients without being buffered by Nginx.

#### Sample Nginx Configuration (`/etc/nginx/sites-available/finally.conf`):

```nginx
server {
    listen 80;
    server_name trading.yourdomain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name trading.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/trading.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/trading.yourdomain.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    # Gzip compression for static assets
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml text/javascript;

    # General API and SPA Routing
    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Dedicated SSE Streaming Route Configuration
    location /api/stream/ {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Connection '';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Disable buffering for real-time SSE delivery
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400s;
        chunked_transfer_encoding off;
    }
}
```

---

### Step 4: Health Monitoring & Maintenance

- **Health Checks**: Monitor `GET https://trading.yourdomain.com/api/health` or `/actuator/health`.
- **Database Backups**: Because SQLite operates as a single file, take automated backups safely using the online backup command:
  ```bash
  # Safe live SQLite backup without downtime
  docker exec finally-prod sqlite3 /app/db/finally.db ".backup /app/db/backup-$(date +%Y%m%d%H%M%S).db"
  ```
- **Log Inspection**:
  ```bash
  docker logs -f --tail 100 finally-prod
  ```

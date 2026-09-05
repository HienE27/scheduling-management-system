# Hospital Scheduler - Docker Deployment Guide

## Quick Start

### 1. Prerequisites
- Docker Engine 20.10+
- Docker Compose 2.0+
- 4GB RAM minimum for all services

### 2. Setup Environment

```bash
# Copy environment file
cp .env.example .env

# Edit .env with your configuration (especially JWT_SECRET for production)
nano .env
```

### 3. Start Services

```bash
# Development mode (from project root)
docker-compose up -d --build

# Or use docker-compose directly
docker-compose up -d --build
```

### 4. Verify Services

```bash
# Check container status
docker-compose ps

# View logs
docker-compose logs -f

# Check individual service
docker-compose logs backend
docker-compose logs frontend
docker-compose logs mysql
```

## Services

| Service | Port | URL |
|---------|------|-----|
| Frontend | 3000 | http://localhost:3000 |
| Backend API | 8080 | http://localhost:8080 |
| Swagger UI | 8080 | http://localhost:8080/swagger-ui.html |
| MySQL | 3306 | localhost:3306 |

## Common Commands

### Build Images
```bash
docker-compose build --no-cache
```

### Stop Services
```bash
docker-compose down
```

### Stop and Remove Volumes (Fresh start)
```bash
docker-compose down -v
```

### Restart Services
```bash
docker-compose restart
```

### Execute Commands in Container
```bash
# Backend shell
docker-compose exec backend sh

# MySQL shell
docker-compose exec mysql mysql -u hospital -p

# View environment variables
docker-compose exec backend env
```

### Import Database
```bash
# Copy SQL file to container
docker cp ./hospital_scheduler_business_final.sql mysql:/tmp/init.sql

# Import
docker-compose exec mysql mysql -u hospital -p hospital_scheduler < ./hospital_scheduler_business_final.sql
```

## Production Deployment

### 1. Use production compose file
```bash
docker-compose -f docker-compose.prod.yml up -d --build
```

### 2. Configure Environment Variables

Set these in `.env` or environment:
- `MYSQL_ROOT_PASSWORD` - Strong password for MySQL root
- `MYSQL_PASSWORD` - Strong password for MySQL user
- `JWT_SECRET` - Secure random string (min 32 chars)
- `NEXT_PUBLIC_API_URL` - Your production domain

### 3. Nginx Reverse Proxy (Optional)

```nginx
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;

    # Frontend
    location / {
        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }

    # Backend API
    location /api {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

## Troubleshooting

### Backend won't start
```bash
# Check logs
docker-compose logs backend

# Check if MySQL is ready
docker-compose exec mysql mysqladmin ping

# Rebuild without cache
docker-compose build --no-cache backend
```

### Frontend build fails
```bash
# Check Node version
docker-compose exec frontend node --version

# Rebuild frontend
docker-compose build --no-cache frontend
```

### Database connection issues
```bash
# Check MySQL is running
docker-compose ps mysql

# Check MySQL logs
docker-compose logs mysql

# Test connection
docker-compose exec mysql mysql -u hospital -phospital123 -e "SHOW DATABASES;"
```

### Clean rebuild
```bash
docker-compose down -v --rmi all
docker system prune -af
docker-compose up -d --build
```

## Data Persistence

Data is stored in Docker volumes:
- `mysql_data` - MySQL database files
- `backend_logs` - Backend application logs

To backup:
```bash
# Backup MySQL
docker-compose exec mysql mysqldump -u hospital -p hospital_scheduler > backup.sql

# Backup volumes
docker run --rm -v hospital_scheduler_mysql_data:/data -v $(pwd):/backup alpine tar czf /backup/mysql_backup.tar.gz /data
```

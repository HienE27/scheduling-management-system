# Enable Real Deployment — Từ CI/CD sang Production

Dự án hiện tại có **CI/CD pipeline hoạt động** (build, test, push Docker images lên GHCR),
nhưng **3 deploy jobs đang ở chế độ echo-only**. File này hướng dẫn từng bước bật deploy thật.

---

## Tổng quan

| Job | Workflow | Branch | Trạng thái |
|-----|----------|--------|-----------|
| `deploy-develop` | `ci.yml` | `develop` | ⚠️ Echo only (commented SSH) |
| `deploy-staging` | `ci.yml` | `main` | ✅ Có script SSH (cần secrets) |
| `deploy-production` | `ci.yml` | `main` | ⚠️ Echo only (commented SSH) |
| `deploy` (backend) | `backend-ci.yml` | any | ⚠️ Echo only |
| `deploy` (frontend) | `frontend-ci.yml` | any | ⚠️ Echo only |

**Để enable**: bỏ comment code trong workflows + set GitHub Secrets.

---

## Bước 1 — Chuẩn bị server

### 1.1 Yêu cầu tối thiểu

- 1 VPS Linux (Ubuntu 22.04 LTS khuyến nghị), tối thiểu 2 vCPU / 4 GB RAM / 40 GB SSD
- Domain trỏ về IP server (A record)
- SSH key pair (private key bạn giữ, public key copy lên server)

### 1.2 Setup server lần đầu

```bash
# SSH vào server
ssh deploy@<your-server-ip>

# Cài Docker + Docker Compose plugin
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker deploy
exit

# Login lại để pick up group
ssh deploy@<your-server-ip>
docker --version
docker compose version
```

### 1.3 Tạo app directory

```bash
sudo mkdir -p /opt/hospital-scheduler
sudo chown deploy:deploy /opt/hospital-scheduler
cd /opt/hospital-scheduler

# Copy docker-compose files
scp docker-compose.staging.yml deploy@<server>:/opt/hospital-scheduler/

# Tạo .env.staging
nano .env.staging
```

---

## Bước 2 — Cấu hình GitHub Secrets

Vào **GitHub repo → Settings → Secrets and variables → Actions**, tạo các secrets sau:

### SSH Secrets (bắt buộc cho staging)

| Secret | Mô tả |
|--------|-------|
| `SSH_STAGING_HOST` | IP/hostname server (vd: `103.98.123.456`) |
| `SSH_STAGING_USER` | User SSH (vd: `deploy`) |
| `SSH_STAGING_KEY` | **Private key** (toàn bộ nội dung, bao gồm `-----BEGIN OPENSSH PRIVATE KEY-----`) |

### Database Secrets (bắt buộc)

| Secret | Mô tả |
|--------|-------|
| `DB_HOST` | MySQL host (vd: `localhost` hoặc RDS endpoint) |
| `DB_PORT` | `3306` |
| `DB_NAME` | `hospital_scheduler` |
| `DB_USER` | `hospital` |
| `DB_PASSWORD` | Password MySQL (mạnh!) |

### JWT Secret (bắt buộc cho production)

| Secret | Mô tả |
|--------|-------|
| `JWT_SECRET` | Min 32 ký tự. Generate: `openssl rand -base64 48` |

### Frontend Public URL (bắt buộc)

| Secret | Mô tả |
|--------|-------|
| `NEXT_PUBLIC_API_URL` | `https://staging.your-domain.com/api/v1` |

### Email (tuỳ chọn — chỉ bật khi muốn gửi mail)

| Secret | Mô tả |
|--------|-------|
| `MAIL_HOST` | vd: `smtp.gmail.com` |
| `MAIL_USERNAME` | Email gửi |
| `MAIL_PASSWORD` | App password (không phải password thường) |
| `APP_EMAIL_ENABLED` | `true` |

---

## Bước 3 — Bật deploy jobs

### 3.1 Staging — `ci.yml`

File `.github/workflows/ci.yml` đã có **script SSH hoạt động** (line 318-355).
Chỉ cần set `SSH_STAGING_HOST` + `SSH_STAGING_USER` + `SSH_STAGING_KEY` là staging tự động chạy.

Kiểm tra health check có pass không:

```bash
# Từ server, test thủ công trước khi để CI chạy
cd /opt/hospital-scheduler
docker compose -f docker-compose.staging.yml --env-file .env.staging pull
docker compose -f docker-compose.staging.yml --env-file .env.staging up -d
sleep 30
curl -sf http://localhost:8080/actuator/health
curl -sf http://localhost:3000
```

### 3.2 Production — `ci.yml`

Hiện đang echo-only. Mở `.github/workflows/ci.yml`, tìm `deploy-production` job,
**bỏ comment block** từ `# - name: Deploy to production via SSH` trở xuống
(line ~382-417), đồng thời thêm secrets `SSH_DEPLOY_HOST`, `SSH_DEPLOY_USER`, `SSH_DEPLOY_KEY`
(tương tự staging nhưng trỏ tới prod server).

### 3.3 Backend CI riêng — `backend-ci.yml`

Bỏ comment khối `# - name: Deploy to server via SSH` (line ~210-230).
Lưu ý: job này chạy **trên cả push tới main lẫn develop**, nên cần điều kiện `if: github.ref == 'refs/heads/main'`
trước job deploy.

### 3.4 Frontend CI riêng — `frontend-ci.yml`

Tương tự `backend-ci.yml`, bỏ comment khối SSH deploy (line ~279-294).

---

## Bước 4 — Setup database lần đầu

### 4.1 Nếu dùng MySQL cùng server

```bash
sudo apt install mysql-server-8.0
sudo mysql_secure_installation

sudo mysql
```

```sql
CREATE DATABASE hospital_scheduler CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'hospital'@'%' IDENTIFIED BY 'your-strong-password';
GRANT ALL PRIVILEGES ON hospital_scheduler.* TO 'hospital'@'%';
FLUSH PRIVILEGES;
```

### 4.2 Nếu dùng managed DB (RDS, Cloud SQL, PlanetScale)

Chỉ cần tạo DB + user, sau đó set `DB_HOST` trỏ tới endpoint.
Spring Boot sẽ tự chạy `ddl-auto=update` để tạo schema khi khởi động lần đầu.

### 4.3 (Tuỳ chọn) Seed data

```bash
# Sau khi deploy xong, có thể seed data qua API:
curl -X POST https://staging.your-domain.com/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"change-me"}'
```

Hoặc dùng file SQL mẫu (nếu có) import qua MySQL client.

---

## Bước 5 — Verify

### 5.1 Trigger deploy bằng push

```bash
git checkout develop
git commit --allow-empty -m "chore: trigger CI/CD test"
git push origin develop
```

Theo dõi: https://github.com/tmHieu20-02/business-trip-management/actions

### 5.2 Hoặc trigger manual

Vào GitHub Actions → chọn workflow → **Run workflow** → chọn branch.

### 5.3 Health checks

```bash
# Backend
curl https://staging.your-domain.com/actuator/health
# Phải trả: {"status":"UP","components":{...}}

# Frontend
curl -I https://staging.your-domain.com

# Database ping từ container
docker exec hospital_scheduler_backend mysqladmin ping \
  -h $DB_HOST -u $DB_USER -p$DB_PASSWORD
```

---

## Bước 6 — Rollback

Nếu deploy mới bị lỗi, rollback trên server:

```bash
ssh deploy@<server>
cd /opt/hospital-scheduler

# Restore compose file backup (workflow tự tạo .bak mỗi lần deploy)
cp docker-compose.staging.yml.bak docker-compose.staging.yml

# Restart với image cũ (workflow đã pull trước đó)
docker compose -f docker-compose.staging.yml --env-file .env.staging up -d

# Hoặc pin về SHA cũ
# Sửa image tag trong compose file:
#   ghcr.io/tmHieu20-02/hospital-scheduler-backend:<old-sha>
```

---

## Bước 7 — Monitoring (khuyến nghị)

### 7.1 Prometheus + Grafana

Project đã enable `management.endpoints.web.exposure.include` có `prometheus`.
Có thể scrape endpoint `/actuator/prometheus` để đưa vào Grafana.

### 7.2 Log aggregation

```bash
# Backend logs có sẵn trong volume
docker exec hospital_scheduler_backend tail -f /app/logs/app.log

# Hoặc dùng Loki + Promtail
```

### 7.3 Uptime monitoring

Dùng UptimeRobot / BetterStack / Healthchecks.io ping `/actuator/health` mỗi 5 phút.

---

## Checklist trước khi go-live

- [ ] Server đã setup Docker + user có group docker
- [ ] `/opt/hospital-scheduler` có quyền write
- [ ] MySQL user có quyền `ALL PRIVILEGES` trên DB
- [ ] JWT_SECRET ≥ 32 chars, KHÁC với giá trị mặc định trong repo
- [ ] GitHub Secrets đã set đầy đủ
- [ ] Domain A record trỏ về IP server
- [ ] HTTPS được setup (Let's Encrypt + Nginx)
- [ ] Health check `/actuator/health` trả `UP`
- [ ] Backup DB tự động hàng ngày
- [ ] Monitoring alerts đã cấu hình

---

## Câu hỏi thường gặp

**Q: Có tốn GitHub Actions minutes không?**
A: Mỗi job ~3-5 phút. Free tier có 2000 phút/tháng → đủ cho team nhỏ.

**Q: Có cách nào deploy nhanh hơn không?**
A: Dùng Railway.app, Render.com, hoặc AWS App Runner — connect GitHub là tự động.

**Q: Workflow fail ở bước SSH thì sao?**
A: Test SSH key trước: `ssh -i ~/.ssh/<key> deploy@<server>` từ máy local.
Nếu pass thì trong CI cũng pass (CI dùng cùng key).

**Q: Làm sao debug khi container crash loop?**
A: `docker logs hospital_scheduler_backend --tail 100`. Logs có sẵn stack trace Java.
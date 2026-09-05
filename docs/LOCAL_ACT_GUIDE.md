# Hướng dẫn chạy GitHub Actions workflow bằng `act` (local)

Công cụ `act` cho phép chạy GitHub Actions workflow trong container Docker local,
giúp developer kiểm tra CI/CD trước khi push mà không tốn GitHub Actions minutes.

---

## 1. Cài đặt

### Windows (winget — đã dùng trong dự án này)

```powershell
winget install --id nektos.act -e --accept-source-agreements --accept-package-agreements
```

### macOS

```bash
brew install act
```

### Linux

```bash
curl -s https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash
```

Sau khi cài, kiểm tra:

```bash
act --version
# act version 0.2.89
```

---

## 2. Yêu cầu: Docker daemon phải chạy

`act` chạy mỗi job bên trong container Docker, nên Docker Desktop phải đang chạy:

```powershell
# Kiểm tra
docker info
# Phải thấy "Server Version" mới OK
```

Nếu Docker chưa chạy:
- **Docker Desktop**: mở app Docker Desktop, đợi whale icon xanh
- **Linux**: `sudo systemctl start docker`

---

## 3. Config image size

`act` hỏi chọn image size lần đầu. Config ở `C:\Users\Admin\AppData\Local\act\actrc`:

```ini
# Medium size (~500MB) — khuyến nghị cho project này
-P ubuntu-latest=ghcr.io/catthehacker/ubuntu:act-latest
--container-architecture linux/amd64
```

**Lưu ý**: `--medium` không phải CLI flag, chỉ set trong actrc.

---

## 4. Cú pháp cơ bản

```bash
# Liệt kê tất cả jobs (rất hữu ích để khám phá)
act -l

# Chạy 1 job cụ thể
act -j <job-name>

# Giới hạn workflow (nếu có trùng tên job)
act -j <job-name> -W .github/workflows/<file>.yml

# Chạy trigger event cụ thể
act push
act pull_request
act workflow_dispatch

# Dry-run (chỉ in plan, không thực thi)
act -n

# Verbose
act -v
```

---

## 5. Chạy các job trong dự án này

### 5.1 Frontend lint + type-check (nhanh nhất, ~2 phút)

```bash
cd E:/DACN/business-trip-management
act -j test -W .github/workflows/frontend-ci.yml
```

### 5.2 Backend test với MySQL service

```bash
act -j test -W .github/workflows/backend-ci.yml
```

Lưu ý: backend test dùng MySQL container, có thể cần expose port khác nếu 3306 đã bận.

### 5.3 Frontend Vitest unit test

```bash
act -j unit -W .github/workflows/frontend-ci.yml
```

### 5.4 PR2 Discovery

```bash
act -W .github/workflows/pr2-discovery.yml
```

### 5.5 Docker build (cần Buildx, image lớn ~10 phút)

```bash
act -j docker-build -W .github/workflows/backend-ci.yml
```

---

## 6. Secrets và ENV

`act` mặc định **không có** GitHub secrets. Cách inject:

```bash
# Cách 1: file .env.local (không commit)
echo "JWT_SECRET=xxx" > .env.local
echo "DB_PASSWORD=123456" >> .env.local
act --secret-file .env.local

# Cách 2: inline
act --secret JWT_SECRET=xxx

# Cách 3: my.secrets (default)
act --secret-file my.secrets
```

---

## 7. Troubleshooting

| Lỗi | Nguyên nhân | Cách sửa |
|------|-------------|----------|
| `failed to connect to docker API` | Docker daemon chưa chạy | Start Docker Desktop |
| `Couldn't get a valid docker connection` | `DOCKER_HOST` sai | Unset biến này, dùng mặc định |
| `unknown flag: --medium` | Sai cú pháp actrc | Đặt `-P` thay vì `--medium` |
| Image pull chậm / fail | Mạng chậm | Đổi image size sang `micro` |
| Job thất bại thiếu tool | Image thiếu tool | Chuyển sang `--large` size |

---

## 8. Khi nào nên dùng `act`?

✅ **Dùng khi**:
- Test nhanh cú pháp YAML workflow
- Debug step nào fail trước khi push
- Dev offline (không có mạng push GitHub)
- Chạy lint/test không tốn CI minutes

❌ **Không thay thế**:
- GitHub Actions chính thức (vẫn cần push để chạy trên cloud)
- Test trên môi trường y hệt production
- Test secrets thật từ GitHub Settings
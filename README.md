# Hệ thống Quản lý Lịch Công Tác

Website quản lý lịch công tác cho phòng chuyên môn với 4 loại lịch (`L01`-`L04`), backend Spring Boot + MySQL và frontend Next.js.

[![CI/CD](https://img.shields.io/github/actions/workflow-status/tmHieu20-02/business-trip-management/ci.yml?branch=develop&label=ci&logo=github)](https://github.com/tmHieu20-02/business-trip-management/actions/workflows/ci.yml)
[![Backend](https://img.shields.io/github/actions/workflow-status/tmHieu20-02/business-trip-management/backend-ci.yml?branch=develop&label=backend&logo=github)](https://github.com/tmHieu20-02/business-trip-management/actions/workflows/backend-ci.yml)
[![Frontend](https://img.shields.io/github/actions/workflow-status/tmHieu20-02/business-trip-management/frontend-ci.yml?branch=develop&label=frontend&logo=github)](https://github.com/tmHieu20-02/business-trip-management/actions/workflows/frontend-ci.yml)

## Tổng quan

- **Backend**: Spring Boot `4.0.6`, Java `17`, Spring Security, JPA, MySQL, SpringDoc OpenAPI
- **Frontend**: Next.js `16.2.6`, React `19`, TypeScript, Tailwind CSS `4`
- **Database**: MySQL schema `hospital_scheduler`
- **API base path**: `/api/v1`
- **Swagger UI**: `http://localhost:8080/swagger-ui.html`

## Nghiệp vụ cốt lõi

Hệ thống quản lý 4 loại lịch:

- `L01` — Lịch trực `24/24`
- `L02` — Lịch thông tầm
- `L03` — Lịch phòng khám dịch vụ
- `L04` — Lịch phòng khám chuyên gia

Các ràng buộc đang được backend kiểm soát qua `ConflictDetectionService`:

- Cùng nhân sự, cùng ngày: `L01` và `L02` không được đồng thời tồn tại
- Cùng nhân sự, cùng ngày: `L03` và `L04` không được đồng thời tồn tại
- Ngày nghỉ bù sau `L01` không được xếp bất kỳ lịch nào khác

Quy tắc nghỉ bù hiện bám theo tài liệu nghiệp vụ:

- Trực `Thứ 2` → nghỉ bù `Thứ 3`
- Trực `Thứ 3` → nghỉ bù `Thứ 4`
- Trực `Thứ 4` → nghỉ bù `Thứ 5`
- Trực `Thứ 5` → nghỉ bù `Thứ 6`
- Trực `Thứ 6` hoặc `Thứ 7` → nghỉ bù `Thứ 3 tuần sau`
- Trực `Chủ Nhật` → nghỉ bù `Thứ 2 tuần sau`

## Cấu trúc thư mục

- `backend/` — API, business rules, seed data, export Excel/PDF
- `frontend/` — giao diện quản trị và dashboard
- `SPEC.md` — tài liệu nghiệp vụ và phạm vi hệ thống
- `QuanLyLichCongTac_v5.md` — mô tả chức năng gốc theo hướng product/spec

## Cách chạy local

### 1. Chuẩn bị

Cần cài sẵn:

- Java `17`
- Maven wrapper hoặc Maven compatible với Spring Boot `4`
- Node.js mới đủ chạy Next.js `16`
- `pnpm`
- MySQL `8.x`

Tạo database:

```sql
CREATE DATABASE hospital_scheduler CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Cấu hình mặc định hiện nằm trong `backend/src/main/resources/application.properties`:

- DB URL: `jdbc:mysql://localhost:3306/hospital_scheduler`
- Username: `root`
- Password: `123456`
- Backend port: `8080`

### 2. Chạy backend

```bash
cd backend
./mvnw spring-boot:run
```

Hoặc trên Windows:

```bash
cd backend
mvnw.cmd spring-boot:run
```

Backend sẽ chạy tại `http://localhost:8080`.

### 3. Chạy frontend

```bash
cd frontend
pnpm install
pnpm dev
```

Frontend sẽ chạy tại `http://localhost:3000`.

Nếu cần chỉ rõ API URL cho frontend, tạo file `.env.local` trong `frontend/`:

```bash
NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1
```

Nếu không cấu hình biến này, frontend đang fallback về `http://localhost:8080/api/v1` trong code.

## Tài khoản seed mặc định

`DataSeeder` seed dữ liệu mẫu khi database còn trống. Tổng cộng **20 nhân sự**: 1 admin, 2 manager, 17 staff.

Tài khoản có sẵn:

| Username | Password | Role | Họ tên |
|---|---|---|---|
| `admin` | `admin123` | ADMIN + MANAGER | Nguyễn Văn An |
| `manager1` | `123456` | MANAGER | Trần Thị Bình |
| `manager2` | `123456` | MANAGER | Lê Hoàng Cường |
| `nvminh` | `123456` | STAFF | Nguyễn Văn Minh |
| `tthuhien` | `123456` | STAFF | Trần Thu Hiền |
| *(+ 15 staff khác)* | `123456` | STAFF | … |

Danh sách đầy đủ: xem `DataSeeder.java` method `seedAdminUser()`.

## Dữ liệu mẫu được seed

Khi database rỗng, hệ thống tự tạo:

- 4 `shift types`: `L01`, `L02`, `L03`, `L04`
- 4 nhóm chuyên môn mẫu
- 1 kỳ `PUBLISHED`: `Kỳ tháng 06/2026`
- 1 kỳ `DRAFT`: `Kỳ tháng 07/2026`
- Một số `shift requirements`
- Một số lịch mẫu, bao gồm cả dữ liệu có conflict để test UI và rule

## Màn hình frontend hiện có

Các route quan trọng trong `frontend/src/app`:

| Route | Mô tả |
|-------|-------|
| `/login` | Đăng nhập |
| `/dashboard` | Dashboard tổng quan |
| `/staff` | Danh sách nhân sự |
| `/staff/create` | Tạo nhân sự |
| `/staff/profile` | Hồ sơ cá nhân |
| `/duty-24` | Lịch trực `L01` |
| `/all-day` | Lịch thông tầm `L02` |
| `/service-clinic` | Lịch phòng khám dịch vụ `L03` |
| `/expert-clinic` | Lịch phòng khám chuyên gia `L04` |
| `/schedule-summary` | Tổng hợp lịch + export |
| `/monthly-schedule` | Bảng lịch tháng + conflicts + coverage |
| `/conflict-check` | Kiểm tra xung đột |
| `/swap-requests` | Yêu cầu đổi ca |
| `/leave-requests` | Đơn nghỉ phép |
| `/notifications` | Thông báo |
| `/reports` | Báo cáo |
| `/reports/staff` | Báo cáo theo nhân sự |
| `/reports/monthly` | Báo cáo theo tháng |
| `/reports/conflicts` | Báo cáo xung đột |
| `/audit-history` | Nhật ký thao tác |
| `/auto-scheduling` | Auto scheduling (M07) |
| `/auto-scheduling/algorithm-config` | Cấu hình thuật toán |
| `/auto-scheduling/history` | Lịch sử chạy |
| `/settings` | Cài đặt |
| `/settings/roles` | **Ma trận phân quyền** (M01-F05) |
| `/requirements` | Yêu cầu nhân sự — cấu hình số nhân sự cần thiết cho từng ngày/loại ca (M07) |
| `/periods` | Quản lý kỳ lịch — CRUD + publish/archive |
| `/holidays` | Quản lý ngày lễ + ngày nghỉ bù |

## API chính hiện có

### Auth

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/logout`

Auth hiện dùng JWT lưu trong cookie HTTP-only tên `medschedule_access_token`.

### Schedule

- `GET /api/v1/schedules/period/{periodId}`
- `GET /api/v1/schedules/period/{periodId}/date/{date}`
- `GET /api/v1/schedules/staff/{staffId}`
- `GET /api/v1/schedules/conflicts/check/{periodId}`
- `POST /api/v1/schedules`
- `PUT /api/v1/schedules/{id}`
- `DELETE /api/v1/schedules/{id}`
- `GET /api/v1/schedules/replacements/{periodId}`

### Period

- `GET /api/v1/periods`
- `GET /api/v1/periods/{id}`
- `POST /api/v1/periods`
- `PUT /api/v1/periods/{id}`
- `POST /api/v1/periods/{id}/publish`
- `POST /api/v1/periods/{id}/archive`

### Dashboard / Export

- `GET /api/v1/dashboard`
- `GET /api/v1/dashboard/shifts`
- `GET /api/v1/dashboard/periods`
- `GET /api/v1/dashboard/workload/period/{periodId}`
- `GET /api/v1/dashboard/export/schedule/{periodId}`
- `GET /api/v1/dashboard/export/schedule/{periodId}/pdf`
- `GET /api/v1/dashboard/export/workload/{periodId}`

### Auto scheduling

- `POST /api/v1/auto-scheduling/preview` — Xem trước lịch
- `POST /api/v1/auto-scheduling/apply` — Áp dụng lịch
- `POST /api/v1/auto-scheduling/save-template` — M07-F10: Lưu thành template
- `GET /api/v1/auto-scheduling/templates` — M07-F10c: Liệt kê templates
- `GET /api/v1/auto-scheduling/templates/{id}` — M07-F10d: Chi tiết template
- `POST /api/v1/auto-scheduling/templates` — M07-F10b: Lưu cấu hình thuật toán thành template
- `POST /api/v1/auto-scheduling/apply-template` — Áp dụng template
- `GET /api/v1/auto-scheduling/metrics/period/{periodId}` — Metrics thuật toán
- `GET /api/v1/auto-scheduling/unassigned-report` — Báo cáo ngày chưa phân công (M07-F06)
- `GET /api/v1/auto-scheduling/suggest-replacements/{scheduleId}` — Đề xuất thay thế (M07-F08)
- `GET /api/v1/auto-scheduling/unassigned/{periodId}` — Danh sách ngày chưa đủ nhân sự
- `GET /api/v1/algorithm-config` — Lấy cấu hình thuật toán
- `PUT /api/v1/algorithm-config` — Cập nhật cấu hình
- `GET /api/v1/algorithm-config/runtime` — Lấy runtime config (greedy_coverage_threshold, balance_score_min, weekend_weight...)
- `PUT /api/v1/algorithm-config/runtime` — Cập nhật runtime config
- `POST /api/v1/algorithm-config/sync-descriptions` — Đồng bộ mô tả params từ code

### Roles & Permissions (M01-F05)

- `GET /api/v1/roles/permissions/matrix` — full role × permission matrix (ADMIN only)
- `POST /api/v1/roles/permissions/toggle` — grant or revoke a permission for a role

### Staff / exchange

- `GET /api/v1/staff`
- `GET /api/v1/staff/active`
- `GET /api/v1/staff/me`
- `POST /api/v1/staff/import`
- `GET /api/v1/schedule-exchanges`
- `GET /api/v1/schedule-exchanges/pending`
- `POST /api/v1/schedule-exchanges/requester/{requesterId}`
- `PUT /api/v1/schedule-exchanges/{id}/approve`
- `PUT /api/v1/schedule-exchanges/{id}/reject`

## Trạng thái triển khai hiện tại

Những phần đã thấy rõ trong code:

### Backend

- CRUD lịch cơ bản cho `L01`-`L04`
- Kiểm tra conflict theo kỳ (8 loại conflict: DUPLICATE_SHIFT, L01_L02_CONFLICT, L03_L04_CONFLICT, COMPENSATION_CONFLICT, MAX_SHIFTS_PER_MONTH, BACK_TO_BACK_SHIFT, INVALID_SHIFT_TYPE, UNAUTHORIZED_SHIFT)
- Tự tính và trả `compensationDate` từ backend
- `max_shifts_per_month` chỉ áp dụng cho L01 (tối đa 5 ca L01/tháng)
- `BACK_TO_BACK_SHIFT` — từ chối tạo ca trực L01 ngay sau L01 của cùng nhân sự
- Email alert gửi khi tạo/cập nhật schedule có conflict (qua `validateAndThrowWithEmail`)
- Publish guard — cảnh báo coverage gaps (non-blocking warning) khi publish period
- Thống kê L03/L04 tích hợp trong `/dashboard/workload/period/{id}`
- Export Excel cho lịch và workload
- Export PDF cho lịch tổng hợp nếu service PDF khả dụng trong môi trường chạy
- Auto scheduling với các luồng preview, run, báo cáo unassigned, workload chart, metrics
- Seed dữ liệu mẫu để demo nhanh (20 nhân sự, 2 kỳ lịch)
- 221 backend unit + integration tests, 300 frontend unit tests

### Frontend

- Hiển thị ngày nghỉ bù trên bảng lịch tháng (`/monthly-schedule`), khóa thao tác trên ô nghỉ bù
- Real-time conflict alerts qua WebSocket
- Shift Requirement management (`/requirements`) — CRUD cấu hình nhân sự cần thiết cho từng ngày/loại ca
- Period management (`/periods`) — CRUD kỳ lịch + publish/archive
- Holiday management (`/holidays`) — CRUD ngày lễ + ngày nghỉ bù
- Ma trận lịch trên Dashboard (hàng=ngày, cột=nhân sự)
- Workflow Stepper cho Auto Scheduling
- Ma trận phân quyền (`/settings/roles`) cho ADMIN
- Inline quick-edit trên calendar
- 300 frontend unit tests + Playwright E2E tests

### M07 — Thuật toán Auto Scheduling

Hệ thống có 3 thuật toán auto-scheduling, chạy qua `AutoSchedulingService`:

| Thuật toán | Mô tả |
|---|---|
| `GREEDY` | Mỗi ngày chọn nhân sự có ít ngày công nhất, theo từng loại lịch |
| `ROUND_ROBIN` | Luân phiên xoay vòng theo thứ tự danh sách nhân sự |
| `BACKTRACKING` | Thử từng phương án, quay lui nếu vi phạm ràng buộc |

Cấu hình thuật toán (17 params trong `algorithm_config`):

**Auto-generation (10 params)** — tự tạo `shift_requirement` khi mở kỳ lịch mới:

| param_key | Mô tả | Mặc định |
|---|---|
| `auto_gen_enabled` | Bật/tắt auto-gen | `true` |
| `auto_gen_l01_per_day` | Số nhân sự L01/ngày | `2` |
| `auto_gen_l02_per_day` | Số nhân sự L02/ngày | `2` |
| `auto_gen_l03_per_day` | Số nhân sự L03/ngày | `2` |
| `auto_gen_l04_per_day` | Số nhân sự L04/ngày | `2` |
| `auto_gen_l01_per_week` | Số L01 tối thiểu/tuần/người | `1` |
| `auto_gen_l02_per_week` | Số L02 tối thiểu/tuần/người | `3` |
| `auto_gen_l03_per_week` | Số L03 tối thiểu/tuần/người | `2` |
| `auto_gen_l04_per_week` | Số L04 tối thiểu/tuần/người | `1` |
| `auto_gen_holiday_mode` | Xử lý ngày lễ: `SKIP`/`PARTIAL` | `SKIP` |

**Runtime algorithm (7 params)** — ảnh hưởng cách thuật toán chạy:

| param_key | Mô tả | Mặc định |
|---|---|
| `max_iterations` | Số vòng lặp tối đa backtracking | `1000` |
| `weekend_weight` | Hệ số phạt cuối tuần (T7/CN) | `2` |
| `overnight_recovery_hours` | Khoảng cách nghỉ L01-L01 | `24` |
| `greedy_coverage_threshold` | Ngưỡng phủ lịch để Greedy dừng sớm (0.5-1.0) | `0.85` |
| `balance_score_min` | Ngưỡng cân bằng tải — Greedy fallback sang Round Robin nếu thấp hơn (0.3-1.0) | `0.70` |
| `auto_compensation_enabled` | Tự động tạo ngày nghỉ bù | `false` |
| `backtrack_time_limit_seconds` | Timeout backtracking (giây) | `60` |

Cấu hình tại `/auto-scheduling/algorithm-config`. Runtime params đang áp dụng hiển thị ngay trên header của `/auto-scheduling`.

Các điểm cần hiểu đúng khi đọc tài liệu:

- `SPEC.md` và `QuanLyLichCongTac_v5.md` chứa nhiều mô tả theo hướng mục tiêu sản phẩm, không phải mục nào cũng đồng nghĩa UI hiện tại đã hoàn thiện 100%
- Một số flow giữa các module đang chưa đồng đều về UX dù backend endpoint đã có
- Quyền trên API không hoàn toàn giống mô tả product-level; ví dụ publish/archive period đang yêu cầu `ADMIN`

## Test hiện có

Backend đang có test ở các vùng chính (204 tests, 0 failures):

- `backend/src/test/java/com/hospital/scheduler/service/ConflictDetectionServiceTest.java` (8 loại conflict)
- `backend/src/test/java/com/hospital/scheduler/service/ScheduleServiceBusinessRulesTest.java`
- `backend/src/test/java/com/hospital/scheduler/service/AutoSchedulingServiceTest.java`
- `backend/src/test/java/com/hospital/scheduler/service/LeaveRequestServiceTest.java`
- `backend/src/test/java/com/hospital/scheduler/service/ScheduleServiceTest.java` (max shifts L01-only, back-to-back)
- `backend/src/test/java/com/hospital/scheduler/service/RoleServiceTest.java`
- `frontend/src/lib/api-client.test.ts` (30 tests)
- `frontend/tests/e2e/*.spec.ts` (Playwright E2E)

Chạy test backend:

```bash
cd backend
./mvnw test
```

Chạy E2E (cần backend chạy trên port 8080 và frontend trên port 3000):

```bash
cd frontend
pnpm playwright test
```

## Tài liệu liên quan

- `SPEC.md` — scope nghiệp vụ và constraints
- `QuanLyLichCongTac_v5.md` — mô tả chức năng chi tiết
- `DEMO_WALKTHROUGH.md` — kịch bản demo đầy đủ cho bảo vệ (15-20 phút)
- `backend/HELP.md` — hướng dẫn backend current-state
- `frontend/README.md` — hướng dẫn frontend current-state
- `screenshots/` — ảnh chụp màn hình phục vụ demo và báo cáo

## Changelog

### v1.1 (06/2026)

**Bug Fixes:**
- Fix API endpoint mismatch: `/schedule-periods` → `/periods` cho requirements page
- Fix null specialty reference trong requirements page (L01/L02 không có specialty)
- Fix scroll-behavior warning trong browser console

**Features:**
- WorkflowStepper 6 bước cho M02-M05
- ConflictSection và ExportReportPanel đồng bộ
- Preview + manual edit + undo cho Auto Scheduling
- GENERATED/PATTERN template support

### v1.0 (05/2026)
- Initial release với 4 loại lịch (L01-L04)
- Backend Spring Boot + MySQL
- Frontend Next.js 16

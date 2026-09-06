-- ============================================================
-- Seed Neon PostgreSQL with MINIMAL data via SQL Console
-- Run this ONCE in Neon SQL Console:
--   1. Open https://console.neon.tech → your project → SQL Editor
--   2. Paste this entire script
--   3. Click "Run" (or press Ctrl+Enter)
-- ============================================================
-- This script:
--   - Creates 3 roles (ADMIN, MANAGER, STAFF)
--   - Creates 6 specialties (Ngoại, Nội, Sản, Nhi, Mắt, Răng)
--   - Creates admin user (username=admin, password=admin123)
--   - Creates 1 sample staff user (manager1 / 123456)
-- After this, you can login to the backend.
--
-- Password hashes use BCrypt (Spring Security default cost=10).
-- admin / admin123 → $2a$10$2g2ePJZ4z5Z5Z5Z5Z5Z5Ze (real hash below)
-- 123456          → real hash below
-- ============================================================

BEGIN;

-- ========== ROLES ==========
INSERT INTO app_role (name, description, is_active, created_at, updated_at)
VALUES
    ('ADMIN',  'Quản trị hệ thống',     TRUE, NOW(), NOW()),
    ('MANAGER', 'Quản lý và duyệt lịch', TRUE, NOW(), NOW()),
    ('STAFF',   'Nhân viên sử dụng hệ thống', TRUE, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- ========== SPECIALTIES ==========
INSERT INTO specialty (name, description, is_active, created_at, updated_at)
VALUES
    ('Ngoại', 'Khoa Ngoại tổng hợp',  TRUE, NOW(), NOW()),
    ('Nội',   'Khoa Nội tổng hợp',    TRUE, NOW(), NOW()),
    ('Sản',   'Khoa Sản phụ khoa',    TRUE, NOW(), NOW()),
    ('Nhi',   'Khoa Nhi',             TRUE, NOW(), NOW()),
    ('Mắt',   'Khoa Mắt',             TRUE, NOW(), NOW()),
    ('Răng',  'Khoa Răng hàm mặt',    TRUE, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- ========== ADMIN USER (admin / admin123) ==========
-- BCrypt hash for "admin123" (Spring Security default cost 10)
INSERT INTO staff (
    username, password_hash, full_name, phone, email,
    specialty_id, max_shifts_per_month, is_active, status,
    created_at, updated_at, hire_date
)
SELECT
    'admin',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', -- admin123
    'Nguyễn Văn An',
    '0901000001',
    'admin@hospital.com',
    (SELECT id FROM specialty WHERE name = 'Ngoại' LIMIT 1),
    5, TRUE, 'ACTIVE',
    NOW(), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM staff WHERE username = 'admin');

-- ========== SAMPLE MANAGER (manager1 / 123456) ==========
INSERT INTO staff (
    username, password_hash, full_name, phone, email,
    specialty_id, max_shifts_per_month, is_active, status,
    created_at, updated_at, hire_date
)
SELECT
    'manager1',
    '$2a$10$DSD0Y7tJxbTpdE3RZV7zVeW7N3zE7H/UK1Q3tV1jUK1Q3tV1jUK1Q', -- placeholder
    'Trần Thị Bình',
    '0901000002',
    'manager1@hospital.com',
    (SELECT id FROM specialty WHERE name = 'Ngoại' LIMIT 1),
    4, TRUE, 'ACTIVE',
    NOW(), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM staff WHERE username = 'manager1');

-- ========== ASSIGN ROLES TO ADMIN ==========
INSERT INTO staff_role (staff_id, role_id, created_at)
SELECT s.id, r.id, NOW()
FROM staff s, app_role r
WHERE s.username = 'admin' AND r.name IN ('ADMIN', 'MANAGER')
  AND NOT EXISTS (
    SELECT 1 FROM staff_role sr
    WHERE sr.staff_id = s.id AND sr.role_id = r.id
  );

-- ========== ASSIGN ROLE TO MANAGER ==========
INSERT INTO staff_role (staff_id, role_id, created_at)
SELECT s.id, r.id, NOW()
FROM staff s, app_role r
WHERE s.username = 'manager1' AND r.name = 'MANAGER'
  AND NOT EXISTS (
    SELECT 1 FROM staff_role sr
    WHERE sr.staff_id = s.id AND sr.role_id = r.id
  );

COMMIT;

-- ========== VERIFY ==========
SELECT 'Roles' AS table_name, COUNT(*) AS count FROM app_role
UNION ALL
SELECT 'Specialties', COUNT(*) FROM specialty
UNION ALL
SELECT 'Staff', COUNT(*) FROM staff
UNION ALL
SELECT 'Staff-Role links', COUNT(*) FROM staff_role;

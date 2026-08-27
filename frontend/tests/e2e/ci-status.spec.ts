import { test, expect, type Page } from '@playwright/test';

/**
 * Smoke test cho trang /admin/ci-status.
 *
 * Strategy: bypass login bằng cách inject fake auth state vào localStorage
 * trước khi navigate. Backend thật không cần chạy vì:
 *   - Page không gọi API backend (chỉ GitHub API)
 *   - RouteGuard chỉ check permission từ localStorage user.permissions
 *   - GitHub API là public, không cần auth
 */

const ADMIN_USER = {
  username: 'admin',
  userId: 1,
  roles: ['ADMIN'],
  permissions: ['APP_CONFIG_VIEW'],
};

async function loginFake(page: Page) {
  await page.goto('/login');
  await page.waitForLoadState('domcontentloaded');
  // Inject auth state into localStorage
  await page.evaluate((user) => {
    window.localStorage.setItem('medschedule.user', JSON.stringify(user));
    window.localStorage.setItem('medschedule.token', 'fake-token-for-test');
  }, ADMIN_USER);
}

test.describe('CI/CD Status page', () => {
  test('renders 5 workflow cards and 4 KPIs', async ({ page }) => {
    const apiCalls: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('api.github.com/repos/')) {
        apiCalls.push(req.url());
      }
    });

    await loginFake(page);

    // Navigate to CI status page — should pass RouteGuard because APP_CONFIG_VIEW is set
    await page.goto('/admin/ci-status');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(4000); // allow GitHub API to respond

    // Page title
    await expect(page.getByRole('heading', { name: 'CI/CD Status' })).toBeVisible();

    // 5 workflow cards (by their label headings)
    await expect(page.getByRole('heading', { name: 'CI/CD Pipeline' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Backend CI/CD' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Frontend CI/CD' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'PR2 Discovery' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Trellis Task Sync' })).toBeVisible();

    // 4 KPI cards (use heading role to disambiguate from status badges)
    await expect(page.getByRole('heading', { name: 'Thành công (30 run gần nhất)' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Thất bại' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Đang chạy' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Tổng run' })).toBeVisible();

    // GitHub API was actually called (proves data flows from real source)
    expect(apiCalls.length).toBeGreaterThan(0);
    expect(apiCalls[0]).toContain('actions/runs');

    // Take a screenshot for visual verification
    await page.screenshot({ path: 'tests/e2e/screenshots/ci-status.png', fullPage: true });
  });

  test('shows error state when API is unreachable', async ({ page }) => {
    // Block GitHub API to simulate network failure
    await page.route('**/api.github.com/**', (route) => route.abort());

    await loginFake(page);
    await page.goto('/admin/ci-status');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(2000);

    // Error banner should be visible
    await expect(page.getByText(/Không tải được dữ liệu từ GitHub/i)).toBeVisible();
  });
});

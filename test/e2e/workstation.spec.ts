import { test, expect } from '@playwright/test';

test.describe('SignalForge Trading Workstation E2E Tests', () => {
  test('Fresh launch should show $10,000 cash balance, streaming watchlist, and AI panel', async ({ page }) => {
    await page.goto('/');

    // Check title & branding
    await expect(page.locator('.title')).toHaveText('SignalForge');
    await expect(page.locator('.subtitle')).toHaveText('AI Trading Workstation');

    // Check default cash balance
    await expect(page.locator('.metric-card').filter({ hasText: 'CASH BALANCE' })).toContainText('$10,000.00');
    await expect(page.locator('.metric-card').filter({ hasText: 'PORTFOLIO VALUE' })).toContainText('$10,000.00');

    // Check watchlist has 10 default tickers
    const rows = page.locator('.ticker-row');
    await expect(rows).toHaveCount(10);
    await expect(page.locator('.watchlist-table')).toContainText('AAPL');
    await expect(page.locator('.watchlist-table')).toContainText('TSLA');
    await expect(page.locator('.watchlist-table')).toContainText('NVDA');

    // Check AI Copilot is present
    await expect(page.locator('.chat-title')).toHaveText('AI COPILOT');
  });

  test('Watchlist operations: add ticker and remove ticker', async ({ page }) => {
    await page.goto('/');

    // Add new ticker 'PYPL'
    await page.locator('.ticker-input').fill('PYPL');
    await page.locator('.btn-add').click();

    // Verify PYPL is now in watchlist
    await expect(page.locator('.watchlist-table')).toContainText('PYPL');
    await expect(page.locator('.ticker-row')).toHaveCount(11);

    // Remove PYPL
    const pyplRow = page.locator('.ticker-row').filter({ hasText: 'PYPL' });
    await pyplRow.locator('.btn-remove').click();

    // Verify count returned to 10
    await expect(page.locator('.ticker-row')).toHaveCount(10);
  });

  test('Cross-origin mutation is rejected without changing the watchlist', async ({ page }) => {
    await page.goto('/');
    const before = await page.locator('.ticker-row').allTextContents();

    const response = await page.request.post('/api/watchlist', {
      data: { ticker: 'ZZZZ' },
      headers: { Origin: 'https://evil.example' },
    });

    expect(response.status()).toBe(403);
    await page.reload();
    await expect(page.locator('.ticker-row')).toHaveCount(before.length);
    await expect(page.locator('.watchlist-table')).not.toContainText('ZZZZ');
  });

  test('Manual Trading: buy shares and observe portfolio update', async ({ page }) => {
    await page.goto('/');

    // Select AAPL
    await page.locator('.ticker-row').filter({ hasText: 'AAPL' }).click();
    await expect(page.locator('.symbol-badge')).toHaveText('AAPL');

    // Set Quantity to 5 and click BUY
    await page.locator('.qty-input').fill('5');
    await page.locator('.btn-buy').click();

    // Toast notification appeared
    await expect(page.locator('.toast-notification')).toBeVisible();
    await expect(page.locator('.toast-notification')).toContainText('BUY');

    // Verify position appears in Positions table
    await expect(page.locator('.positions-table')).toContainText('AAPL');
    await expect(page.locator('.positions-table')).toContainText('5.00');

    // Verify Cash balance decreased below $10,000
    const cashText = await page.locator('.metric-card').filter({ hasText: 'CASH BALANCE' }).innerText();
    expect(cashText).not.toContain('$10,000.00');

    // Verify Heatmap has position tile
    await expect(page.locator('.treemap-tile')).toContainText('AAPL');
  });

  test('AI Assistant: execute trade and analyze via natural language chat', async ({ page }) => {
    await page.goto('/');

    // Send AI message asking to buy MSFT
    await page.locator('.chat-input').fill('buy 5 shares of MSFT');
    await page.locator('.btn-send').click();

    // Verify assistant responds with executed trade confirmation
    await expect(page.locator('.message-bubble').filter({ hasText: 'SIGNALFORGE AI' }).last()).toBeVisible({ timeout: 10000 });
    await expect(page.locator('.action-card').filter({ hasText: 'MSFT' })).toBeVisible();

    // Verify MSFT holding now appears in positions table
    await expect(page.locator('.positions-table')).toContainText('MSFT');
  });
});

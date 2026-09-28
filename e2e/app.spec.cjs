const { test, expect } = require('@playwright/test');

async function login(page) {
  await page.goto('/login');
  await page.fill('input[name=username]', 'dev');
  await page.fill('input[name=password]', 'e2e-pass');
  await page.click('button[type=submit]');
  await page.waitForURL('**/');
}

for (const scheme of ['dark', 'light']) {
  for (const viewport of [{ name: 'desktop', width: 1440, height: 1000 }, { name: 'mobile', width: 390, height: 844 }]) {
    test(`all screens ${viewport.name} ${scheme}`, async ({ page }) => {
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
      await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
      await page.setViewportSize(viewport);
      await login(page);

      await expect(page.locator('#sidebar-state')).toHaveText('Bot läuft', { timeout: 20_000 });
      await expect(page.locator('#active-streams .stream-card:not(.placeholder)')).toHaveCount(2, { timeout: 20_000 });
      await expect(page.locator('#active-streams')).toContainText('papaplatte');
      await expect(page.locator('#active-streams')).toContainText('<img src=x');
      expect(await page.evaluate(() => window.__xss)).toBeUndefined();
      await expect(page.locator('#feed')).toContainText('Bonus-Truhe geöffnet');
      await expect(page.locator('#stat-today')).not.toHaveText('–');
      await expect(page.locator('#live-label')).toHaveText('Live');
      await page.waitForTimeout(4500);
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-overview.png`, fullPage: true });

      await page.click('nav a[href="#channels"]');
      await expect(page.locator('#channel-list .channel-row')).toHaveCount(4);
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-channels.png`, fullPage: true });
      await page.click('#channel-list [data-channel="zarbex"]');
      await expect(page.locator('#channel-dialog')).toBeVisible();
      await expect(page.locator('#detail-name')).toHaveText('zarbex');
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-detail.png` });
      await page.keyboard.press('Escape');

      await page.click('nav a[href="#drops"]');
      await expect(page.locator('#campaigns')).toContainText('Hazmat Suit');
      await expect(page.locator('#campaigns')).toContainText('63 %');
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-drops.png`, fullPage: true });

      await page.click('nav a[href="#raffles"]');
      await expect(page.locator('#raffle-problem')).toContainText('chat:edit');
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-raffles.png`, fullPage: true });

      await page.click('nav a[href="#bot"]');
      await expect(page.locator('#bot-state')).toHaveText('Läuft');
      await expect(page.locator('#device-login')).toContainText('tomlurkt');
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-bot.png`, fullPage: true });

      await page.click('nav a[href="#settings"]');
      await expect(page.locator('#join-commands')).toHaveValue(/!join/);
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-settings.png`, fullPage: true });
      expect(errors).toEqual([]);
    });
  }
}

test('actions: add channel, invalid settings, raffle toggle, stop/start', async ({ page }) => {
  await login(page);
  await page.goto('/#channels');
  await page.fill('#add-channel-input', 'Papaplatte_2');
  await page.click('#add-channel button[type=submit]');
  await expect(page.locator('#toast')).toContainText('papaplatte_2', { ignoreCase: true });

  await page.goto('/#settings');
  await page.fill('#delay-min', '90');
  await page.fill('#delay-max', '10');
  await page.click('#settings-form button[type=submit]');
  await expect(page.locator('#save-message')).toContainText('Verzögerung');

  await page.goto('/#raffles');
  const toggle = page.locator('#raffle-toggles input').first();
  await toggle.click();
  await expect(page.locator('#toast')).toContainText('Keine Raffles mehr');

  await page.goto('/#bot');
  await expect(page.locator('#bot-toggle')).toHaveText('Bot stoppen', { timeout: 20_000 });
  await page.click('#bot-toggle');
  await expect(page.locator('#bot-state')).toHaveText('Pausiert', { timeout: 30_000 });
  await page.click('#bot-toggle');
  await expect(page.locator('#sidebar-state')).toHaveText(/Bot (startet|läuft)/, { timeout: 20_000 });
});

test('motion layer: nav indicator, entrance, draw-on, reduced-motion off', async ({ page }) => {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'no-preference' });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await login(page);
  await expect(page.locator('#overview.is-entering')).toHaveCount(1, { timeout: 10_000 });
  await expect(page.locator('#overview.is-entering')).toHaveCount(0, { timeout: 5_000 });

  const aligned = async () => page.evaluate(() => {
    const ind = document.querySelector('.nav-indicator').getBoundingClientRect();
    const act = document.querySelector('nav a[aria-current]').getBoundingClientRect();
    return Math.abs(ind.top - act.top) < 2 && Math.abs(ind.height - act.height) < 2;
  });
  expect(await aligned()).toBe(true);

  await page.click('nav a[href="#channels"]');
  for (let i = 0; i < 6; i++) await page.screenshot({ path: `shots/motion-${i}.png` }), await page.waitForTimeout(90);
  await expect(page.locator('#channels.is-entering')).toHaveCount(1);
  await expect(page.locator('.sparkline polyline[pathLength="1"]').first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(700);
  expect(await aligned()).toBe(true);
  const rowAnim = await page.locator('.channel-row').nth(1).evaluate(el => getComputedStyle(el).animationName);
  expect(['rise', 'none']).toContain(rowAnim);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(500);
  expect(await page.evaluate(() => {
    const ind = document.querySelector('.nav-indicator').getBoundingClientRect();
    const act = document.querySelector('nav a[aria-current]').getBoundingClientRect();
    return Math.abs(ind.left - act.left) < 2 && Math.abs(ind.width - act.width) < 2;
  })).toBe(true);
  expect(errors).toEqual([]);
});

const { test, expect } = require('@playwright/test');

async function go(page, id) {
  await page.locator(`nav a[href="#${id}"]:visible, a.settings-link[href="#${id}"]:visible`).first().click();
}

async function login(page) {
  // Keep e2e offline: preview images instead of live Twitch players.
  await page.addInitScript(() => localStorage.setItem('video', '0'));
  await page.goto('/');
  await page.waitForURL('**/login.html');
  await page.fill('#username', 'dev');
  await page.fill('#password', 'e2e-password-123');
  await page.click('#login-form button[type=submit]');
  await page.waitForURL(url => !url.pathname.includes('login'));
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

      await expect(page.locator('#sidebar-state')).toHaveText('Bot running', { timeout: 20_000 });
      await expect(page.locator('#slots .slot h3')).toHaveText(['papaplatte', 'zarbex'], { timeout: 20_000 });
      await expect(page.locator('#slots')).toContainText('<img src=x');
      await expect(page.locator('#slots .slot-media img').first()).toHaveAttribute('src', /static-cdn\.jtvnw\.net\/previews-ttv\/live_user_papaplatte/);
      expect(await page.evaluate(() => window.__xss)).toBeUndefined();
      await expect(page.locator('#feed')).toContainText('Bonus chest opened');
      await expect(page.locator('#stat-drops')).not.toHaveText('–');
      await expect(page.locator('#state-banner')).toBeHidden();
      await expect(page.locator('#slots .pin-icon').first()).toBeHidden();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow).toBeLessThanOrEqual(0);
      if (viewport.name === 'mobile') {
        await expect(page.locator('.tabbar')).toBeVisible();
        await expect(page.locator('.sidebar')).toBeHidden();
        expect(await page.locator('#channel-search').evaluate(el => parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(16);
        // Tab labels must stay on one line (English labels are longer than German ones were).
        expect(await page.locator('.tabs button').first().evaluate(el => el.getBoundingClientRect().height)).toBeLessThan(60);
      }
      await expect(page.locator('#stat-today')).not.toHaveText('–');
      await expect(page.locator('#live-label')).toHaveText('Live');
      await page.waitForTimeout(4500);
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-overview.png`, fullPage: true });

      await go(page, 'channels');
      await expect(page.locator('#channel-list .channel-row')).toHaveCount(4);
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-channels.png`, fullPage: true });
      await page.click('#channel-list [data-channel="zarbex"]');
      await expect(page.locator('#channel-dialog')).toBeVisible();
      await expect(page.locator('#detail-name')).toHaveText('zarbex');
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-detail.png` });
      await page.keyboard.press('Escape');

      await go(page, 'drops');
      await expect(page.locator('#campaigns')).toContainText('Hazmat Suit');
      await expect(page.locator('#campaigns')).toContainText('63%');
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-drops.png`, fullPage: true });

      await go(page, 'raffles');
      await expect(page.locator('#raffle-problem')).toContainText('chat:edit');
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-raffles.png`, fullPage: true });

      await go(page, 'bot');
      await expect(page.locator('#bot-state')).toHaveText('Running');
      await expect(page.locator('#device-login')).toContainText('tomlurkt');
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-bot.png`, fullPage: true });

      await go(page, 'settings');
      await expect(page.locator('#join-commands')).toHaveValue(/!join/);
      await page.screenshot({ path: `shots/${viewport.name}-${scheme}-settings.png`, fullPage: true });
      expect(errors).toEqual([]);
    });
  }
}

test('wrong password shows an error', async ({ page }) => {
  await page.goto('/login.html');
  await page.fill('#username', 'dev');
  await page.fill('#password', 'definitely-wrong');
  await page.click('#login-form button[type=submit]');
  await expect(page.locator('#login-error')).toBeVisible();
  await page.screenshot({ path: 'shots/login.png' });
});

test('actions: add channel, invalid settings, raffle toggle, stop/start', async ({ page }) => {
  await login(page);
  await page.goto('/#channels');
  await page.fill('#add-channel-input', 'Papaplatte_2');
  await page.click('#add-channel button[type=submit]');
  await expect(page.locator('#toast')).toContainText('papaplatte_2', { ignoreCase: true });

  // Slow settings load: whatever the user already typed must survive it (CI caught this race).
  await page.route('**/api/settings', async route => {
    if (route.request().method() === 'GET') await new Promise(r => setTimeout(r, 1500));
    await route.continue();
  });
  await page.goto('/#settings');
  await page.fill('#delay-min', '90');
  await page.fill('#delay-max', '10');
  await page.click('#settings-form button[type=submit]');
  await expect(page.locator('#save-message')).toContainText('Delay');
  await page.unrouteAll({ behavior: 'ignoreErrors' });

  await page.goto('/#raffles');
  const toggle = page.locator('#raffle-toggles input').first();
  await toggle.click();
  await expect(page.locator('#toast')).toContainText('No more raffles');

  await page.goto('/#bot');
  await expect(page.locator('#bot-toggle')).toHaveText('Stop bot', { timeout: 20_000 });
  await page.click('#bot-toggle');
  await expect(page.locator('#bot-state')).toHaveText('Paused', { timeout: 30_000 });
  await page.click('#bot-toggle');
  await expect(page.locator('#sidebar-state')).toHaveText(/Bot (starting|running)/, { timeout: 20_000 });
});

test('slots, order and motion', async ({ page }) => {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error' && !m.text().includes('static-cdn')) errors.push(m.text()); });
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await login(page);
  await expect(page.locator('#slots .slot h3')).toHaveText(['papaplatte', 'zarbex'], { timeout: 20_000 });

  // Pin trymacs to slot 1: the bot switches over, the card follows.
  await page.selectOption('#slot-select-0', 'trymacs');
  await expect(page.locator('#toast')).toContainText('Slot 1 now belongs to trymacs');
  await expect(page.locator('#slots .slot').first().locator('h3')).toHaveText('trymacs', { timeout: 15_000 });
  await expect(page.locator('#slots .slot').first().locator('.slot-label')).toHaveText('Slot 1 · pinned');
  await page.selectOption('#slot-select-0', '');
  await expect(page.locator('#toast')).toContainText('automatic again');

  // Reorder with the keyboard buttons, then by dragging the grip.
  await go(page, 'channels');
  await expect(page.locator('#channels.is-entering')).toHaveCount(1);
  await expect(page.locator('#channel-list .channel-row')).toHaveCount(4);
  await page.click('#channel-list [data-move="1"][data-login="papaplatte"]');
  await expect(page.locator('#toast')).toContainText('Order saved');
  await expect(page.locator('#channel-list .channel-row').nth(1)).toHaveAttribute('data-login', 'papaplatte');

  const grip = page.locator('#channel-list [data-grip="gronkh"]');
  const top = page.locator('#channel-list .channel-row').first();
  const gb = await grip.boundingBox(), tb = await top.boundingBox();
  await page.mouse.move(gb.x + gb.width / 2, gb.y + gb.height / 2);
  await page.mouse.down();
  await page.mouse.move(gb.x + gb.width / 2, tb.y + 4, { steps: 12 });
  await page.mouse.up();
  await expect(page.locator('#channel-list .channel-row').first()).toHaveAttribute('data-login', 'gronkh');
  await page.waitForTimeout(5000);
  await page.reload();
  await go(page, 'channels');
  await expect(page.locator('#channel-list .channel-row').first()).toHaveAttribute('data-login', 'gronkh', { timeout: 10_000 });
  await page.screenshot({ path: 'shots/order-after-reload.png', fullPage: true });

  expect(errors).toEqual([]);
});

test('drops catalogue, watchlist, follow refresh and lurk settings', async ({ page }) => {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await login(page);
  await go(page, 'drops');
  await expect(page.locator('#catalogue-grid .catalogue-item')).toHaveCount(5, { timeout: 15_000 });
  // Twitch quests pay on any stream of the game and say when they are already earned.
  const aurora = page.locator('.catalogue-item', { hasText: 'Aurora Cape' });
  await expect(aurora).toContainText('Earned ✓');
  await expect(aurora).toContainText('Twitch quest · any Minecraft stream');
  await expect(aurora).toContainText('no account link needed');
  await expect(page.locator('#catalogue-grid .catalogue-item').last()).toContainText('Aurora Cape');
  // Sub-gift drops must not look like something lurking can earn; channel-restricted ones name their channels.
  await expect(page.locator('.catalogue-item', { hasText: 'D&D Ampersand Badge' })).toContainText("lurking can't earn it");
  await expect(page.locator('.catalogue-item', { hasText: 'D&D Ampersand Badge' })).toContainText('1 sub');
  await expect(page.locator('.catalogue-item', { hasText: 'Minecraft Live 2026' })).toContainText('Only on papaplatte, gronkh');
  await page.selectOption('#drop-filter', 'quests');
  await expect(page.locator('#catalogue-grid .catalogue-item')).toHaveCount(1);
  await page.selectOption('#drop-filter', 'all');

  // Quests show Twitch's own progress, and earned code rewards open their code.
  await page.click('[data-drop-tab="campaigns"]');
  await expect(page.locator('#campaigns .campaign', { hasText: 's0phtember' })).toContainText(/137 \/ 1[.,]?440 minutes/);
  const creeper = page.locator('#campaigns .campaign', { hasText: 'Corrupted Creeper Cape' });
  await expect(creeper).toContainText('Ready to claim');
  await expect(creeper.getByRole('link', { name: 'Claim on Twitch' })).toHaveAttribute('href', 'https://www.twitch.tv/drops/inventory');
  const auroraProgress = page.locator('#campaigns .campaign', { hasText: 'Aurora Cape' });
  await expect(auroraProgress).toContainText('Twitch quest');
  await auroraProgress.getByRole('button', { name: 'Show code' }).click();
  await expect(page.locator('#code-value')).toHaveText('AURO-RA12-CAPE');
  await expect(page.locator('#code-redeem')).toHaveAttribute('href', 'https://www.minecraft.net/redeem');
  await expect(page.locator('#code-redeem')).toContainText('Redeem on minecraft.net');
  await expect(page.locator('#code-expires')).toContainText('Valid until');
  await page.screenshot({ path: 'shots/drops-code.png' });
  await page.locator('#code-dialog .close-dialog').click();
  await expect(page.locator('#code-value')).toHaveText('');

  await page.click('[data-drop-tab="inventory"]');
  await expect(page.locator('#inventory-grid .inventory-item').first()).toContainText('Aurora Cape');
  // Badges have no code, so no button.
  await expect(page.locator('#inventory-grid .inventory-item', { hasText: 'Pichu' }).getByRole('button')).toHaveCount(0);
  await page.locator('#inventory-grid .inventory-item', { hasText: 'Aurora Cape' }).getByRole('button', { name: 'Show code' }).click();
  await expect(page.locator('#code-value')).toHaveText('AURO-RA12-CAPE');
  await page.keyboard.press('Escape');
  await page.click('[data-drop-tab="catalogue"]');
  await page.fill('#drop-search', 'twitch cape');
  await expect(page.locator('#catalogue-grid .catalogue-item')).toHaveCount(1);
  await expect(page.locator('#catalogue-grid')).toContainText('Minecraft Live 2026');
  await page.fill('#drop-search', '');
  await page.selectOption('#drop-filter', 'upcoming');
  await expect(page.locator('#catalogue-grid .catalogue-item')).toHaveCount(1);
  await page.selectOption('#drop-filter', 'all');

  await page.fill('#watch-input', 'Minecraft');
  await page.click('#watch-form button[type=submit]');
  await expect(page.locator('#watch-chips')).toContainText('Minecraft');
  await expect(page.locator('#catalogue-grid .catalogue-item.watched')).toHaveCount(2, { timeout: 10_000 });
  await page.screenshot({ path: 'shots/drops-catalogue.png', fullPage: true });
  await page.click('[data-unwatch="Minecraft"]');
  await expect(page.locator('#watch-chips')).not.toContainText('Minecraft');

  await go(page, 'channels');
  await page.click('#refresh-follows');
  await expect(page.locator('#toast')).toContainText('Syncing follows');
  await expect(page.locator('#channel-list .channel-row[data-login="newfollow"]')).toHaveCount(1, { timeout: 20_000 });

  await go(page, 'settings');
  await expect(page.locator('#lurk-message')).toHaveValue('!lurk');
  await page.fill('#lurk-message', '!lurk bin im Hintergrund');
  await page.selectOption('#lurk-repeat', '120');
  await page.click('#settings-form button[type=submit]');
  await expect(page.locator('#toast')).toContainText('saved');
  await page.reload();
  await go(page, 'settings');
  await expect(page.locator('#lurk-message')).toHaveValue('!lurk bin im Hintergrund');
  await expect(page.locator('#lurk-repeat')).toHaveValue('120');
  expect(errors).toEqual([]);
});

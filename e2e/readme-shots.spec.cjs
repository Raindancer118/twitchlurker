// Renders the README screenshots with fictional data: TL_SHOWCASE=1 TL_FAKE_RUNNER=showcase_miner.py npx playwright test readme-shots
const { test, expect } = require('@playwright/test');
const path = require('path');

test.skip(!process.env.TL_SHOWCASE, 'only for regenerating README screenshots');
test.setTimeout(240_000);

const OUT = path.join(__dirname, '..', 'docs', 'screenshots');
const ART = path.join(__dirname, 'showcase');
const LOGINS = ['lunaplays', 'pixelbaer', 'nordlicht_tv', 'retrohannah', 'bytebandit', 'kaffeeklatsch'];

async function prepare(page, scheme, video = false) {
  // Serve generated artwork instead of Twitch's CDN, so no real stream thumbnails end up in the README.
  await page.route('https://static-cdn.jtvnw.net/**', route => {
    const url = route.request().url();
    const live = url.match(/live_user_([a-z0-9_]+)/);
    const file = live ? `preview-${Math.max(0, LOGINS.indexOf(live[1])) % 6}.png`
      : url.includes('/showcase/') ? url.split('/showcase/')[1] : 'preview-0.png';
    route.fulfill({ path: path.join(ART, file), contentType: 'image/png' });
  });
  await page.addInitScript(([s, v]) => {
    localStorage.setItem('theme', s);
    localStorage.setItem('video', v ? '1' : '0');
  }, [scheme, video]);
  await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
}

async function login(page) {
  await page.goto('/');
  await page.waitForURL('**/login.html');
  await page.fill('#username', 'dev');
  await page.fill('#password', 'e2e-password-123');
  await page.click('#login-form button[type=submit]');
  await page.waitForURL(url => !url.pathname.includes('login'));
  await expect(page.locator('#live-label')).toHaveText('Live', { timeout: 15_000 });
}

async function go(page, id) {
  await page.locator(`nav a[href="#${id}"]:visible, a.settings-link[href="#${id}"]:visible`).first().click();
  await page.waitForTimeout(1200);
}

test('readme screenshots', async ({ browser }) => {
  // Let the showcase miner produce some point history for sparklines first.
  const warm = await browser.newPage();
  await prepare(warm, 'light');
  await login(warm);
  await warm.evaluate(() => fetch('/api/drops/watch', { method: 'PUT', headers: { 'Content-Type': 'application/json',
    'X-XSRF-TOKEN': decodeURIComponent((document.cookie.match(/XSRF-TOKEN=([^;]+)/) || [])[1] || '') },
    body: JSON.stringify({ games: ['Minecraft'] }) }));
  await warm.waitForTimeout(45_000);
  await warm.close();

  for (const scheme of ['dark', 'light']) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await prepare(page, scheme);
    await login(page);
    await page.waitForTimeout(2500);
    await page.screenshot({ path: `${OUT}/overview-${scheme}.png` });
    await go(page, 'channels');
    await page.screenshot({ path: `${OUT}/channels-${scheme}.png` });
    await go(page, 'drops');
    await page.screenshot({ path: `${OUT}/drops-${scheme}.png` });
    await go(page, 'settings');
    await page.screenshot({ path: `${OUT}/settings-${scheme}.png` });
    await ctx.close();

    const mctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
    const m = await mctx.newPage();
    await prepare(m, scheme);
    await login(m);
    await m.waitForTimeout(2500);
    await m.screenshot({ path: `${OUT}/mobile-overview-${scheme}.png` });
    await go(m, 'channels');
    await m.screenshot({ path: `${OUT}/mobile-channels-${scheme}.png` });
    await go(m, 'drops');
    await m.screenshot({ path: `${OUT}/mobile-drops-${scheme}.png` });
    await mctx.close();
  }
  const lctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const lp = await lctx.newPage();
  await prepare(lp, 'dark');
  await lp.goto('/login.html');
  await lp.waitForTimeout(800);
  await lp.screenshot({ path: `${OUT}/login-dark.png` });
  await lctx.close();
});

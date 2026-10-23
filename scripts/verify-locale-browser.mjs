import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, join } from 'node:path';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { closeDrawer, gotoRoute, withDrawer } from './control-nav.mjs';
const root = resolve('frontend/control');
const { chromium } = createRequire(join(process.env.EATWHAT_PLAYWRIGHT_MODULE, 'package.json'))('playwright');
const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
let writes = 0, lookups = 0;
const caps = { topology: 'SINGLE_CLUSTER', maximumActiveSites: 16, allocationSlots: 1024, leadershipHandoffEnabled: true, businessSessionShared: true, deploymentExecutionEnabled: false };
const leadership = { state: 'HELD', localLeader: true, generation: 1, localControllerId: a, leaderControllerId: a, handoffEnabled: true, handoff: { state: 'NONE' }, controllers: [{ id: a, name: 'campus-control', sharedState: true }, { id: b, name: 'outside-control', sharedState: true }] };
const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://localhost').pathname;
    let data;
    if (path === '/auth/login') data = { code: 0, data: { tokenName: 'satoken', tokenValue: 'synthetic-locale-browser-token' } };
    else if (path === '/auth/logout') data = { code: 0 };
    else if (path.startsWith('/admin/control/v1')) {
      if (req.method === 'POST') writes++;
      if (path.endsWith('/capabilities')) data = caps;
      else if (path.endsWith('/leadership')) data = leadership;
      else if (path === '/admin/control/v1/cluster') data = { clusterId: a, epoch: 1, members: [], pendingChanges: [], worker: { running: true, lastCode: 'AWAITING_FIRST_JOIN' }, leadership };
      else if (path === '/admin/control/v1/cluster/audit') data = [{ id: 1, actor: '資料庫管理員', action: 'CONTROL_HANDOFF_REQUEST', site_id: 'campus', request_id: a, epoch: 1, created_at: '2026-10-17T00:00:00Z' }];
      else if (path.includes('/test-runs/')) { lookups++; res.statusCode = 404; data = { code: 'NOT_FOUND' }; }
      else data = [];
    } else {
      const file = resolve(root, '.' + (path === '/' ? '/index.html' : path));
      assert.ok(file.startsWith(root + '/'));
      res.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[extname(file)] ?? 'text/plain');
      res.end(await readFile(file)); return;
    }
    res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data));
  } catch { res.statusCode = 500; res.end('{}'); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
const steps = [], errors = [], external = [];
const record = value => { steps.push(value); console.log(value); };
page.on('pageerror', error => errors.push(error.message));
page.on('request', req => { if (!req.url().startsWith(origin + '/')) external.push(req.url()); });
const output = resolve('output/playwright/control-locale'); await mkdir(output, { recursive: true });
page.setDefaultTimeout(12000);
const login = async () => {
  await page.locator('#login-username').fill('admin'); await page.locator('#login-password').fill('synthetic');
  await page.locator('#login-submit').click(); await page.locator('#cluster-view').waitFor({ state: 'visible' });
};
try {
  await page.goto(origin);
  assert.equal(await page.locator('html').getAttribute('lang'), 'zh-Hans');
  assert.equal(await page.locator('#login-title').textContent(), '管理员登录');
  assert.equal(await page.locator('#locale-control').isVisible(), false);
  assert.equal(await page.locator('#brand-sub').isVisible(), false);
  assert.equal(await page.locator('#login-view p:visible').count(), 0);
  await page.screenshot({ path: join(output, 'login-1440.png') }); record('minimal_login_defaults_to_simplified');
  await login(); await page.locator('#locale-control').waitFor({ state: 'visible' });
  await page.waitForFunction(() => document.getElementById('cl-handoff-title').textContent === '切换主中控');
  assert.equal(await page.locator('#locale-label').textContent(), '界面语言');
  await gotoRoute(page, 'audit');
  await page.waitForFunction(() => document.querySelector('#cl-audit')?.textContent.includes('資料庫管理員'));
  assert.match(await page.locator('#cl-audit').textContent(), /資料庫管理員/); record('signed_in_language_control_and_raw_audit_actor_preserved');
  await gotoRoute(page, 'control');
  await page.locator('#cl-handoff-target').selectOption(b);
  await page.locator('#cl-handoff-submit').click(); await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#dialog-title').textContent(), '确认切换主中控');
  await page.locator('#locale-select').evaluate(select => { select.value = 'zh-Hant'; select.dispatchEvent(new Event('change', { bubbles: true })); });
  assert.equal(await page.locator('#dialog-title').textContent(), '確認切換主中控');
  assert.equal(await page.locator('#confirm-dialog').evaluate(dialog => dialog.open), true);
  assert.equal(await page.locator('#cl-handoff-target').inputValue(), b);
  await page.locator('#dialog-cancel').click(); assert.equal(writes, 0); record('locale_switch_keeps_open_confirmation_target_and_sends_nothing');
  await page.locator('#locale-select').selectOption('zh-Hans');
  await gotoRoute(page, 'advanced/tasks');
  await page.locator('#cl-run-lookup-form').evaluate(form => { form.closest('details').open = true; });
  await page.locator('#cl-run-lookup-id').fill(a);
  await page.locator('#cl-run-lookup-form button').click();
  await page.waitForFunction(() => document.getElementById('cl-run-result').textContent.length > 0);
  assert.equal(lookups, 1);
  assert.match(await page.locator('#cl-run-result').textContent(), /找不到此任务/);
  await page.locator('#locale-select').selectOption('zh-Hant');
  assert.equal(await page.locator('#cl-handoff-title').textContent(), '切換主中控');
  assert.match(await page.locator('#cl-run-result').textContent(), /找不到此任務/);
  await page.locator('#locale-select').selectOption('zh-Hans');
  assert.equal(await page.locator('#cl-handoff-title').textContent(), '切换主中控');
  assert.equal(await page.locator('#cl-run-lookup-id').inputValue(), a);
  record('dynamic_updates_and_repeated_switches_preserve_input_values');
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({ path: join(output, 'console-390.png'), fullPage: true }); record('mobile_console_language_control_has_no_overflow');
  await withDrawer(page, () => page.locator('#locale-select').selectOption('zh-Hant'));
  await closeDrawer(page);
  const saved = await page.evaluate(() => Object.fromEntries(Object.entries(localStorage)));
  assert.deepEqual(saved, { 'eatwhat.control.locale': 'zh-Hant' });
  await page.reload(); assert.equal(await page.locator('html').getAttribute('lang'), 'zh-Hant');
  assert.equal(await page.locator('#login-title').textContent(), '管理員登入');
  assert.equal(await page.locator('#locale-control').isVisible(), false); record('reload_remembers_only_language_and_keeps_session_in_memory');
  await login(); await withDrawer(page, () => page.locator('#logout-button').click());
  assert.equal(await page.locator('#locale-control').isVisible(), false); record('logout_hides_language_control');
  const blocked = await browser.newPage();
  await blocked.addInitScript(() => Object.defineProperty(window, 'localStorage', { get() { throw new Error('blocked'); } }));
  await blocked.goto(origin); assert.equal(await blocked.locator('#login-title').textContent(), '管理员登录'); await blocked.close(); record('blocked_storage_does_not_break_login');
  assert.deepEqual(errors, []); assert.deepEqual(external, []); assert.equal(writes, 0);
  await writeFile(join(output, 'verification.json'), JSON.stringify({ passed: true, steps, pageErrors: errors, externalRequests: external }, null, 2));
} catch (error) { await page.screenshot({ path: join(output, 'failure.png'), fullPage: true }); throw error; }
finally { await browser.close(); server.closeAllConnections(); await new Promise(done => server.close(done)); }

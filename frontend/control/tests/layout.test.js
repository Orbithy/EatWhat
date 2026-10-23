// 靜態版面檢查：防止手機寬度（例如 390px）因 grid／fieldset／表格 min-content 造成整頁橫向溢出。
// 這只檢查 CSS 規則與結構；實際 scrollWidth 仍需在瀏覽器量測。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const css = readFileSync(join(root, 'styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
const render = readFileSync(join(root, 'render.js'), 'utf8');

// 取出所有「選擇器 { 宣告 }」；@media 內的規則也會被取出（外層 @media 文字不含在選擇器中）。
const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, sel, body]) => ({
  selectors: sel.split(',').map((s) => s.trim()),
  body,
}));

function declarations(selector) {
  return rules.filter((r) => r.selectors.includes(selector)).map((r) => r.body).join(';');
}

test('主要 grid 容器使用 minmax(0, 1fr)，避免隱式欄以 min-content 撐寬', () => {
  for (const sel of ['.console', '.panel', '.form']) {
    assert.match(declarations(sel), /grid-template-columns:\s*minmax\(0,\s*1fr\)/, `${sel} 缺少 minmax(0, 1fr)`);
  }
});

test('grid-template-columns 不使用裸 1fr（會等同 minmax(auto, 1fr)）', () => {
  for (const r of rules) {
    const m = r.body.match(/grid-template-columns:\s*([^;]+)/);
    if (!m) continue;
    const stripped = m[1].replace(/minmax\([^)]*\)/g, '');
    assert.ok(!/(^|[\s(,])1fr\b/.test(stripped), `${r.selectors.join(', ')} 的 grid-template-columns 含裸 1fr：${m[1]}`);
  }
});

test('fieldset 取消預設 min-inline-size: min-content', () => {
  assert.match(declarations('fieldset'), /min-inline-size:\s*0/);
  assert.match(declarations('fieldset'), /min-width:\s*0/);
});

test('子項 min-width: 0 涵蓋版面容器', () => {
  for (const sel of ['.console > *', '.panel > *', '.form > *', '.split > *', '.panel-head > *']) {
    assert.match(declarations(sel), /min-width:\s*0/, `${sel} 缺少 min-width: 0`);
  }
});

test('select 不以選項文字寬度撐開', () => {
  const body = declarations('.field select');
  assert.match(body, /min-width:\s*0/);
  assert.match(body, /max-width:\s*100%/);
});

test('表格與導覽列在自身容器橫捲，而非撐寬頁面', () => {
  const wrap = declarations('.table-wrap');
  assert.match(wrap, /overflow-x:\s*auto/);
  assert.match(wrap, /max-width:\s*100%/);
  assert.match(wrap, /min-width:\s*0/);
  assert.match(declarations('.section-nav'), /overflow-x:\s*auto/);
});

test('不以 overflow hidden 隱藏整頁溢出', () => {
  assert.ok(!/overflow-x:\s*hidden/.test(css), '不可使用 overflow-x: hidden');
  for (const sel of ['html', 'body', '.console', 'main']) {
    assert.ok(!/overflow:\s*hidden/.test(declarations(sel)), `${sel} 不可 overflow: hidden`);
  }
});

test('長代碼、SHA 與徽章可換行', () => {
  assert.match(declarations('.mono'), /overflow-wrap:\s*anywhere/);
  const badge = declarations('.badge');
  assert.ok(!/white-space:\s*nowrap/.test(badge), '.badge 不可 nowrap');
  assert.match(badge, /overflow-wrap:\s*anywhere/);
  assert.match(declarations('.kv dd'), /overflow-wrap:\s*anywhere/);
});

test('頂列只在寬螢幕固定；900px 以下側欄改為隱藏抽屜', () => {
  const base = rules.find((r) => r.selectors.includes('.appbar') && /position:\s*sticky/.test(r.body));
  assert.ok(base, '寬螢幕 .appbar 需要 sticky');
  const narrow = css.slice(css.indexOf('@media (max-width: 900px)'));
  assert.match(narrow, /\.appbar\s*\{[^}]*position:\s*static/);
  assert.match(narrow, /\.sidebar\s*\{[^}]*position:\s*fixed[^}]*visibility:\s*hidden/);
  assert.match(narrow, /\.shell\.nav-open \.sidebar\s*\{[^}]*visibility:\s*visible/);
  assert.ok(!/\.topbar/.test(css), '舊 .topbar 規則已移除');
});

test('HTML 保留驗收使用的穩定 id', () => {
  const html = readFileSync(join(root, 'index.html'), 'utf8');
  const ids = [
    'login-form', 'login-username', 'login-password', 'login-submit', 'login-error', 'console-view',
    'fault-form', 'fault-site', 'fault-channel', 'fault-seconds', 'fault-limit', 'fault-impact', 'fault-blocking', 'fault-warnings', 'fault-submit', 'fault-result',
    'confirm-dialog', 'dialog-title', 'dialog-lines', 'dialog-cancel', 'dialog-confirm',
    'plan-form', 'plan-create', 'plan-reload', 'plan-view', 'deploy-execute', 'lock-reasons', 'release-select', 'release-details',
    'pair-bar', 'site-grid', 'audit', 'audit-limit', 'gc', 'suite-list', 'active-run', 'run-cancel', 'run-history',
    'run-lookup-form', 'run-lookup-id', 'capabilities', 'connection', 'stale-banner', 'overview', 'env-chip', 'cap-summary',
  ];
  for (const id of ids) {
    const count = (html.match(new RegExp(`id="${id}"`, 'g')) ?? []).length;
    assert.equal(count, 1, `id="${id}" 應恰好出現一次`);
  }
});

test('技術欄位以原生 details/summary 收合，且重繪保留展開狀態', () => {
  assert.match(render, /h\(\s*'details'/);
  assert.match(render, /h\('summary'/);
  assert.match(render, /function replaceKeepingDisclosures/);
  assert.match(render, /replaceKeepingDisclosures\(el, SITE_IDS\.map/);
  for (const key of ['-identity', '-queue-detail', '-channel-stats']) assert.ok(render.includes(key), `缺少收合區 ${key}`);
});

test('render.js 中每個表格都包在 .table-wrap 內', () => {
  const tables = (render.match(/\{ class: 'table' \}/g) ?? []).length;
  const wraps = (render.match(/class: 'table-wrap'/g) ?? []).length;
  assert.ok(tables > 0);
  assert.equal(wraps, tables);
});

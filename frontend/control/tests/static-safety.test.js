// 靜態掃描：確認原始碼沒有不安全的 DOM 寫入、持久化儲存、外部資源或部署執行入口。
// 只讀取本目錄（tests 的上一層）內的檔案。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const sourceFiles = readdirSync(root).filter((f) => /\.(js|html|css)$/.test(f));
const read = (f) => readFileSync(join(root, f), 'utf8');

test('原始碼檔案存在', () => {
  for (const f of ['index.html', 'styles.css', 'app.js', 'api.js', 'state.js', 'render.js', 'dom.js', 'format.js', 'validate.js', 'session.js', 'constants.js']) {
    assert.ok(sourceFiles.includes(f), `缺少 ${f}`);
  }
});

test('不使用 HTML 字串注入、eval，只有語言偏好模組可持久化語言選項', () => {
  const forbidden = [
    /\.innerHTML\b/,
    /\.outerHTML\b/,
    /insertAdjacentHTML/,
    /document\.write/,
    /\beval\s*\(/,
    /new\s+Function\s*\(/,
    /sessionStorage/,
    /indexedDB/,
    /document\.cookie/,
    /\bconsole\.(log|info|debug|warn|error)\b/,
    /history\.(pushState|replaceState)/,
    /location\.(hash|search)\s*=/,
  ];
  for (const f of sourceFiles.filter((x) => x.endsWith('.js'))) {
    const text = read(f);
    if (f !== 'locale.js') assert.ok(!/localStorage/.test(text), `${f} 不應讀寫語言偏好儲存`);
    for (const re of forbidden) assert.ok(!re.test(text), `${f} 含有 ${re}`);
  }
});

test('不載入外部資源（無 http(s) URL、@import、url()）', () => {
  for (const f of sourceFiles) {
    const text = read(f);
    assert.ok(!/https?:\/\//i.test(text), `${f} 含有外部 URL`);
  }
  const html = read('index.html');
  for (const [, attr, value] of html.matchAll(/\s(src|href)="([^"]*)"/gi)) {
    assert.ok(value.startsWith('./') || value.startsWith('#'), `index.html 的 ${attr}="${value}" 必須是本目錄相對路徑或錨點`);
  }
  const css = read('styles.css');
  assert.ok(!/@import/i.test(css));
  assert.ok(!/url\(/i.test(css));
  assert.ok(!/@font-face/i.test(css));
});

test('fetch 只在 api.js 中使用', () => {
  for (const f of sourceFiles.filter((x) => x.endsWith('.js') && x !== 'api.js')) {
    assert.ok(!/\bfetch\s*\(/.test(read(f)), `${f} 不應直接呼叫 fetch`);
  }
});

test('HTML：CSP、無 inline 腳本/事件/樣式，部署執行按鈕停用', () => {
  const html = read('index.html');
  assert.match(html, /http-equiv="Content-Security-Policy"/);
  assert.match(html, /script-src 'self'/);
  assert.match(html, /connect-src 'self'/);
  assert.ok(!/\son[a-z]+\s*=/i.test(html), 'HTML 不可有 inline 事件');
  assert.ok(!/\sstyle\s*=/i.test(html), 'HTML 不可有 inline style');
  const scripts = html.match(/<script\b[^>]*>/gi) ?? [];
  assert.equal(scripts.length, 1);
  assert.match(scripts[0], /type="module"/);
  assert.match(scripts[0], /src="\.\/app\.js"/);
  const exec = html.match(/<button[^>]*id="deploy-execute"[^>]*>/);
  assert.ok(exec, '需要部署執行按鈕');
  assert.match(exec[0], /\sdisabled[\s>]/);
  assert.match(exec[0], /aria-disabled="true"/);
  // 不提供任意 API 基底輸入。
  assert.ok(!/api\s*base|apiBase|基礎路徑/i.test(html));
});

test('部署只以既有計畫 UUID 執行，前端保留確認與重新檢查', () => {
  const ui = read('deployment-ui.js');
  assert.match(ui, /await confirmDialog/);
  assert.match(ui, /getPlan\(captured.id\)/);
  assert.match(ui, /recheck\(captured\)/);
  assert.match(ui, /execute\(captured.id\)/);
  assert.match(read('app.js'), /mountDeployment/);
});

test('登入成功碼為 0 且 API 基底固定', () => {
  const constants = read('constants.js');
  assert.match(constants, /CONTROL_BASE = '\/admin\/control\/v1'/);
  assert.match(constants, /LOGIN_PATH = '\/auth\/login'/);
  assert.match(constants, /LOGIN_SUCCESS_CODE = 0;/);
});

test('自動化動作只能由使用者觸發：app.js 不在載入或輪詢中呼叫寫入 API', () => {
  const app = read('app.js');
  const writes = ['postFault', 'startTestRun', 'cancelTestRun', 'createDeploymentPlan'];
  for (const name of writes) {
    const count = (app.match(new RegExp(`client\\.${name}\\(`, 'g')) ?? []).length;
    assert.equal(count, 1, `${name} 應只在單一使用者事件處理器中呼叫`);
  }
  const refreshBody = app.slice(app.indexOf('async function refreshDynamic'), app.indexOf('async function refreshNow'));
  for (const name of writes) assert.ok(!refreshBody.includes(name), `輪詢不可呼叫 ${name}`);
});

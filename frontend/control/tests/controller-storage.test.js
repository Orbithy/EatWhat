// 中控與業務安裝任務的目標物件儲存（storage）：只取公開鍵、網址過濾、未知保留未知、不影響完成判定、秘密不回顯。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as ts from '../tasks-state.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const ID = '7c1e2b9a-41d3-4f6e-9a20-5d8b3e6f1c04';
const uuid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const steps = () => ts.CONTROLLER_PHASES.map((phase, i) => ({ phase, id: uuid(i + 1), state: 'SUCCEEDED',
  ...(phase === 'DEPENDENCY_LINK' ? { operations: [['KEY', 'TARGET'], ['SOURCE', 'SOURCE'], ['CONNECT', 'TARGET']].map(([operation, role]) => ({ operation, role, nativeId: uuid(i + 1), state: 'SUCCEEDED' })) } : {}) }));
const job = (storage) => ts.normalizeControllerJob({ id: ID, kind: 'CONTROLLER_INSTALL', state: 'SUCCEEDED', requiresReapproval: false, requiresReconciliation: false, steps: steps(), ...(storage === undefined ? {} : { storage }) }, ID);
const RUSTFS_SOURCE = { mode: 'SOURCE', provider: 'RUSTFS', endpoint: 'http://127.0.0.1:9000', region: 'us-east-1', bucket: 'eatwhat', pathStyle: true, 'public-base-url': 'https://files.example.org/eatwhat', 'cdn-endpoint': null, credentialsConfigured: true };
const R2_CUSTOM = { mode: 'CUSTOM', provider: 'R2', endpoint: 'https://abc123.r2.cloudflarestorage.com', region: 'auto', bucket: 'eatwhat-sg', pathStyle: false, 'public-base-url': null, 'cdn-endpoint': 'https://cdn.example.org', credentialsConfigured: true };

test('RustFS 沿用來源：公開欄位齊全、無問題', () => {
  const st = job(RUSTFS_SOURCE).storage;
  assert.deepEqual([st.provided, st.valid, st.mode, st.provider, st.endpoint, st.bucket, st.region, st.pathStyle, st.publicBaseUrl, st.cdnEndpoint, st.credentialsConfigured],
    [true, true, 'SOURCE', 'RUSTFS', 'http://127.0.0.1:9000', 'eatwhat', 'us-east-1', true, 'https://files.example.org/eatwhat', null, true]);
  assert.deepEqual(st.problems, []);
});

test('R2 自訂：region auto、pathStyle false、CDN 地址', () => {
  const st = job(R2_CUSTOM).storage;
  assert.deepEqual([st.mode, st.provider, st.region, st.pathStyle, st.cdnEndpoint, st.publicBaseUrl], ['CUSTOM', 'R2', 'auto', false, 'https://cdn.example.org', null]);
  assert.deepEqual(st.problems, []);
});

test('舊回應無 storage、非物件、缺欄位：如實說明，不補成健康', () => {
  assert.deepEqual(job(undefined).storage, { provided: false, valid: false, problems: [] });
  assert.deepEqual(job(null).storage, { provided: false, valid: false, problems: [] });
  for (const bad of ['RUSTFS', [], 1, true]) assert.equal(job(bad).storage.valid, false, JSON.stringify(bad));
  const sparse = job({ provider: 'RUSTFS' }).storage;
  assert.equal(sparse.valid, true);
  for (const re of [/設定來源未知/, /缺少S3 API 地址/, /缺少桶名稱/, /缺少區域/, /沒有明確說明憑據是否已封存/]) assert.ok(sparse.problems.some((p) => re.test(p)), re.source);
  assert.equal(sparse.credentialsConfigured, null, '缺值不補成已封存');
  assert.equal(job({ ...RUSTFS_SOURCE, credentialsConfigured: 'true', pathStyle: 'true' }).storage.credentialsConfigured, null);
  assert.equal(job({ ...RUSTFS_SOURCE, pathStyle: 'true' }).storage.pathStyle, null);
});

test('未知提供者／mode 保留未知（只回顯安全代碼）', () => {
  const st = job({ ...RUSTFS_SOURCE, provider: 'MINIO', mode: 'MIRROR' }).storage;
  assert.deepEqual([st.provider, st.providerRaw, st.mode, st.modeRaw], [null, 'MINIO', null, 'MIRROR']);
  const odd = job({ ...RUSTFS_SOURCE, provider: 'rustfs<script>', mode: { a: 1 } }).storage;
  assert.deepEqual([odd.provider, odd.providerRaw, odd.mode, odd.modeRaw], [null, null, null, null]);
});

test('異常網址：帶帳密、query、fragment、非 http(s)、空白都不回顯', () => {
  const bads = ['https://AKIA123:secret@s3.example.org', 'https://user@s3.example.org', 'https://s3.example.org/?X-Amz-Signature=abc', 'https://s3.example.org/?', 'https://s3.example.org/#token=abc',
    'ftp://s3.example.org', 'javascript:alert(1)', 'https://s3.example.org/a b', 'https://s3.example.org/\nx', 'not a url', 'https://' + 'a'.repeat(2050)];
  for (const v of bads) {
    assert.equal(ts.safeStorageUrl(v), null, v);
    for (const key of ['endpoint', 'public-base-url', 'cdn-endpoint']) {
      const st = job({ ...RUSTFS_SOURCE, [key]: v }).storage;
      assert.ok(!JSON.stringify(st).includes(v), `${key} ${v.slice(0, 40)}`);
      assert.ok(st.problems.some((p) => /格式異常/.test(p)), key);
    }
  }
  for (const v of ['https://s3.example.org', 'http://127.0.0.1:9000', 'https://files.example.org/a/b']) assert.equal(ts.safeStorageUrl(v), v);
  assert.equal(ts.safeStorageUrl(null), null);
  // 桶與區域含控制字元或過長也不回顯
  const st = job({ ...RUSTFS_SOURCE, bucket: 'a\nb', region: 'x'.repeat(200) }).storage;
  assert.equal(st.bucket, null);
  assert.equal(st.region, null);
});

test('額外秘密欄位不正規化到畫面資料', () => {
  const secretValues = ['AKIAEXAMPLE', 'SECRETEXAMPLEVALUE', 'ENCRYPTEDBLOB', 'BINDINGHASH'];
  const st = job({ ...R2_CUSTOM, 'access-key': secretValues[0], 'secret-key': secretValues[1], accessKey: secretValues[0], secretKey: secretValues[1], encryptedProperties: secretValues[2], binding: secretValues[3], extra: { x: 1 } }).storage;
  const json = JSON.stringify(st);
  for (const v of secretValues) assert.ok(!json.includes(v), v);
  assert.deepEqual(Object.keys(st).sort(), ['bucket', 'cdnEndpoint', 'credentialsConfigured', 'endpoint', 'mode', 'modeRaw', 'pathStyle', 'problems', 'provided', 'provider', 'providerRaw', 'publicBaseUrl', 'region', 'valid']);
});

test('storage 不影響既有完成判定：缺失或異常都不改變「已完成」與否', () => {
  for (const s of [undefined, RUSTFS_SOURCE, { provider: 'X' }, 'bad']) assert.equal(ts.controllerJobDisplay(job(s)).complete, true, JSON.stringify(s));
  const unknown = ts.normalizeControllerJob({ id: ID, kind: 'CONTROLLER_INSTALL', state: 'OUTCOME_UNKNOWN', requiresReapproval: false, requiresReconciliation: true, steps: steps(), storage: RUSTFS_SOURCE }, ID);
  assert.equal(ts.controllerJobDisplay(unknown).complete, false, '儲存設定完整也不會讓結果未知變完成');
});

test('頁面靜態：只讀區塊，憑據已封存說明不代表可連線或健康；沒有新增寫入入口', () => {
  const ui = readFileSync(join(root, 'tasks-ui.js'), 'utf8');
  assert.match(ui, /'已封存（不代表可連線或健康）'/);
  assert.match(ui, /storageSection\(j\.storage\)/);
  assert.match(ui, /回應沒有提供目標物件儲存設定（舊任務或舊後端）；狀態未知。/);
  const sec = ui.slice(ui.indexOf('function storageSection'), ui.indexOf('function renderController'));
  assert.ok(!/button|access|secret|encrypted|binding/i.test(sec), '區塊沒有按鈕，也不讀秘密鍵');
  const html = readFileSync(join(root, 'index.html'), 'utf8');
  const panel = html.slice(html.indexOf('id="tasks-controller-panel"'), html.indexOf('</section>', html.indexOf('id="tasks-controller-panel"')));
  assert.deepEqual((panel.match(/<button[^>]*>[^<]*<\/button>/g) ?? []).map((b) => b.replace(/<[^>]+>/g, '')), ['重新讀取', '關閉']);
});

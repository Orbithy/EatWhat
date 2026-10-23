// 中控已配置的專用密鑰（credentialId）與領導租約接管模式（takeoverMode，唯讀）。
// 補充 scripts/test-control-credential-references.mjs：API 白名單、伺服器檢視正規化、顯示文字與畫面靜態約束。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as ss from '../servers-state.js';
import { normalizeLeadership, takeoverModeText, TAKEOVER_MODES } from '../cluster-state.js';
import { createControlClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const SID = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const KEY = { publicKey: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample', type: 'ssh-ed25519', fingerprint: 'SHA256:abc' };
const OPT = { id: 'singapore-deploy', label: '新加坡專用部署密鑰', authentication: 'PRIVATE_KEY' };
const CAP = ss.credentialInventoryCapability({ sshCredentials: [OPT] });
const PRIV = ss.privilegeCapability({ sshPrivileges: ['NONE', 'SUDO'] });

function form(over = {}) {
  const f = { name: 'sg', location: 'REMOTE', host: '203.0.113.40', port: '22', username: 'ubuntu', authentication: 'PRIVATE_KEY', credential: '', keyIndex: 0, confirmed: true, forceHostKey: false,
    privilege: 'NONE', privilegeCap: PRIV, credentialSource: 'OPERATOR_INVENTORY', credentialId: OPT.id, inventoryCap: CAP, ...over };
  f.probe = { signature: ss.addressSignature(f), keys: [KEY] };
  return f;
}
const base = { id: SID, name: 'sg', revision: 2, location: 'REMOTE', host: '203.0.113.40', port: 22, username: 'ubuntu', authentication: 'PRIVATE_KEY', hasCredential: true, hostKey: KEY };

test('能力：id 格式（首字母英文、1–64 位英數 _ -）、label 必填；舊後端與空清單分開處理', () => {
  assert.deepEqual(CAP, { supported: true, options: [OPT], reason: null });
  assert.deepEqual(ss.credentialInventoryCapability({}), { supported: false, options: [], reason: null }, '舊後端不提示也不顯示');
  const empty = ss.credentialInventoryCapability({ sshCredentials: [] });
  assert.equal(empty.supported, true);
  assert.match(empty.reason, /尚未配置/);
  for (const id of ['1key', '-k', '', 'a'.repeat(65), 'a b', 'k/../x', 'k.pem']) {
    assert.equal(ss.credentialInventoryCapability({ sshCredentials: [{ ...OPT, id }] }).supported, false, id);
  }
  assert.equal(ss.credentialInventoryCapability({ sshCredentials: [{ ...OPT, id: 'a'.repeat(64) }] }).supported, true);
  for (const bad of [{ ...OPT, label: '' }, { ...OPT, label: 3 }, { id: OPT.id, authentication: 'PRIVATE_KEY' }, [OPT], { ...OPT, authentication: undefined }]) {
    const c = ss.credentialInventoryCapability({ sshCredentials: [bad] });
    assert.equal(c.supported, false, JSON.stringify(bad));
    assert.match(c.reason, /格式無效/);
  }
  // 部分有效也不採用（不猜選）
  assert.deepEqual(ss.credentialInventoryCapability({ sshCredentials: [OPT, { id: 'x', label: 'y', authentication: 'CERT' }] }).options, []);
  // 清單只保留 id／label／authentication，額外欄位（如路徑）不帶入
  assert.deepEqual(Object.keys(ss.credentialInventoryCapability({ sshCredentials: [{ ...OPT, path: '/etc/k' }] }).options[0]).sort(), ['authentication', 'id', 'label']);
});

test('表單：引用模式不送手動值、不接受密碼認證；切回手動維持原規則', () => {
  const body = ss.serverRequestBody(form({ credential: '-----BEGIN OPENSSH PRIVATE KEY-----\nx\n-----END OPENSSH PRIVATE KEY-----' }), { requestId: SID });
  assert.equal(body.credentialId, OPT.id);
  assert.ok(!('credential' in body));
  assert.ok(ss.serverFormCheck(form({ authentication: 'PASSWORD' })).errors.some((e) => /只能使用私鑰/.test(e)));
  assert.ok(ss.serverFormCheck(form({ credentialId: '' })).errors.some((e) => /請選擇要使用的專用密鑰/.test(e)));
  assert.ok(ss.serverFormCheck(form({ credentialSource: 'FILE' })).errors.some((e) => /憑據來源/.test(e)));
  const manual = form({ credentialSource: 'MANUAL', credentialId: OPT.id });
  assert.ok(ss.serverFormCheck(manual).errors.some((e) => /請貼上私鑰/.test(e)), '新增手動仍需憑據');
  assert.ok(!('credentialId' in (ss.serverRequestBody({ ...manual, credential: '-----BEGIN OPENSSH PRIVATE KEY-----\nb3Bl\n-----END OPENSSH PRIVATE KEY-----' }, { requestId: SID }) ?? {})));
  // 未提供來源（舊畫面／舊呼叫）視為手動
  const legacy = form({ credentialSource: undefined, credentialId: undefined, inventoryCap: undefined, authentication: 'PASSWORD', credential: 'pw' });
  assert.deepEqual(Object.keys(ss.serverRequestBody(legacy, { requestId: SID })).filter((k) => /credential/i.test(k)), ['credential']);
});

test('編輯：引用來源的伺服器可改用手動新密鑰，或手動伺服器改用引用；PASSWORD 改引用不要求新密碼', () => {
  const invServer = ss.normalizeServer({ ...base, credentialSource: 'OPERATOR_INVENTORY', credentialId: OPT.id });
  const toManual = ss.serverRequestBody(form({ credentialSource: 'MANUAL', credentialId: '', credential: '-----BEGIN OPENSSH PRIVATE KEY-----\nb3Bl\n-----END OPENSSH PRIVATE KEY-----' }), { server: invServer, requestId: SID });
  assert.ok(toManual.credential && !('credentialId' in toManual));
  const pwServer = ss.normalizeServer({ ...base, authentication: 'PASSWORD', credentialSource: 'MANUAL' });
  const toInv = ss.serverRequestBody(form(), { server: pwServer, requestId: SID });
  assert.equal(toInv.credentialId, OPT.id);
  assert.equal(toInv.expectedRevision, 2);
  assert.ok(ss.serverFormCheck(form({ credentialSource: 'MANUAL', authentication: 'PASSWORD' }), invServer).errors.some((e) => /改變認證方式/.test(e)), '手動改密碼仍需新憑據');
});

test('伺服器檢視：來源缺欄位為 null，MANUAL／OPERATOR_INVENTORY 照用，未知如實；顯示只含 label／id', () => {
  assert.equal(ss.normalizeServer(base).credentialSource, null);
  assert.equal(ss.credentialSourceText(ss.normalizeServer(base)), null, '舊後端不顯示來源');
  const inv = ss.normalizeServer({ ...base, credentialSource: 'OPERATOR_INVENTORY', credentialId: OPT.id });
  assert.equal(ss.credentialSourceText(inv, CAP), '中控已配置的專用密鑰：新加坡專用部署密鑰（singapore-deploy）');
  assert.equal(ss.credentialSourceText(inv, ss.credentialInventoryCapability({})), '中控已配置的專用密鑰（singapore-deploy）', '目前中控沒有該選項仍能辨識');
  assert.match(ss.credentialSourceText(ss.normalizeServer({ ...base, credentialSource: 'OPERATOR_INVENTORY', credentialId: '../etc/key' })), /未提供識別碼/, '不合法 id 不顯示');
  assert.equal(ss.credentialSourceText(ss.normalizeServer({ ...base, credentialSource: 'MANUAL' })), '手動輸入的憑據');
  assert.equal(ss.credentialSourceText(ss.normalizeServer({ ...base, credentialSource: 'VAULT_V2' })), '憑據來源未知（VAULT_V2）');
  assert.equal(ss.normalizeServerList({ servers: [], sshCredentials: [OPT] }).sshCredentials.length, 1);
});

test('API：credentialId 格式、只配私鑰、不得同時帶 credential；其他未知欄位仍拒絕', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(201, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  const body = ss.serverRequestBody(form(), { requestId: SID });
  await client.createServer(body);
  assert.equal(fetchImpl.calls[0].body.credentialId, OPT.id);
  assert.ok(!('credential' in fetchImpl.calls[0].body));
  for (const bad of [{ credentialId: '1x' }, { credentialId: '' }, { credentialId: 7 }, { authentication: 'PASSWORD' }, { credential: 'k' }, { credentialPath: '/etc/k' }]) {
    await assert.rejects(client.createServer({ ...body, ...bad }), (e) => e.kind === 'validation', JSON.stringify(bad));
  }
  await assert.rejects(client.updateServer(SID, { ...body, credential: 'k' }), (e) => e.kind === 'validation');
  assert.equal(fetchImpl.calls.length, 1);
});

test('靜態：來源選擇預設手動；切換清空手動欄位；引用時固定私鑰並隱藏手動欄位；選項只顯示 label／id', () => {
  const html = read('index.html');
  assert.match(html, /<fieldset id="server-credential-source" class="choice-grid" hidden>/);
  assert.match(html, /name="server-credential-source" value="MANUAL" checked/);
  assert.match(html, /<select id="server-credential-id">/);
  const ui = read('servers-ui.js');
  assert.match(ui, /setRadio\('server-credential-source', 'MANUAL'\);/);
  assert.match(ui, /\.\.\.\(inventory \? \{ credential: '' \} : \{\}\)/);
  const sw = ui.slice(ui.indexOf("if (t.name === 'server-credential-source')"), ui.indexOf("if (t.name === 'server-hostkey')"));
  assert.ok(sw.includes('clearSecrets()') && sw.includes("els.credId.value = ''") && sw.includes("setRadio('server-auth', 'PRIVATE_KEY')"));
  assert.match(ui, /`\$\{o\.label\}（\$\{o\.id\}）`/);
  assert.ok(!/o\.path|\.path\b/.test(ui.slice(ui.indexOf('function fillCredentialOptions'), ui.indexOf('function renderForm'))));
  assert.match(ui, /els\.passwordField\.hidden = key \|\| inventory;/);
  assert.match(ui, /els\.keyField\.hidden = !key \|\| inventory;/);
});

test('takeoverMode：已知兩種照聲明顯示；缺少／未知／錯型別一律「接管模式未聲明」且不改變其他欄位', () => {
  assert.deepEqual(TAKEOVER_MODES, ['MANUAL_HANDOFF_ONLY', 'AUTOMATIC']);
  const lead = (takeoverMode) => normalizeLeadership({ state: 'HELD', localLeader: true, generation: 3, expiresAt: '2026-10-17T00:00:00Z', ...(takeoverMode === undefined ? {} : { takeoverMode }) });
  assert.equal(takeoverModeText(lead('MANUAL_HANDOFF_ONLY')), '僅手動交接');
  assert.equal(takeoverModeText(lead('AUTOMATIC')), '租約過期後可自動接管');
  for (const v of [undefined, null, '', 'automatic', 'MANUAL', 1, true, {}]) {
    const l = lead(v);
    assert.equal(takeoverModeText(l), '接管模式未聲明', String(v));
    assert.ok(!('takeoverMode' in l), '未知值不保留成看似已聲明');
    assert.deepEqual([l.state, l.localLeader, l.generation], ['HELD', true, 3]);
  }
  assert.equal(takeoverModeText(null), '接管模式未聲明', '沒有領導資料');
  // 不影響寫入條件：AUTOMATIC 的備用程序仍不可寫
  const standby = normalizeLeadership({ state: 'HELD', localLeader: false, generation: 3, takeoverMode: 'AUTOMATIC' });
  assert.equal(standby.localLeader, false);
  const render = read('cluster-render.js');
  assert.match(render, /fact\('接管模式', takeoverModeText\(lead\)/);
  assert.ok(!/takeoverMode[^\n]*(button|data-action|disabled)/.test(render), '不新增操作開關');
});

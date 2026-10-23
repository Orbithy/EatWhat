// SSH 執行權限（privilege：NONE／SUDO）：能力判斷、表單驗證、請求本文、顯示與 root 前置條件。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as ss from '../servers-state.js';
import { createControlClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const SID = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const KEY = { publicKey: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample', type: 'ssh-ed25519', fingerprint: 'SHA256:abc' };
const FULL = ss.privilegeCapability({ sshPrivileges: ['NONE', 'SUDO'] });
const OLD = ss.privilegeCapability({});

function form(over = {}) {
  const f = { name: 'overseas', location: 'REMOTE', host: '203.0.113.40', port: '22', username: 'ubuntu', authentication: 'PRIVATE_KEY', credential: '-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ==\n-----END OPENSSH PRIVATE KEY-----', keyIndex: 0, confirmed: true, forceHostKey: false, privilege: 'NONE', privilegeCap: FULL, ...over };
  f.probe = { signature: ss.addressSignature(f), keys: [KEY] };
  return f;
}

test('能力：只有 sshPrivileges 陣列含 SUDO 才能選免密 sudo；舊後端或格式錯只允許直接執行', () => {
  assert.deepEqual([FULL.supported, FULL.sudo], [true, true]);
  assert.deepEqual([OLD.supported, OLD.sudo, OLD.reason], [false, false, null]);
  for (const v of ['SUDO', ['SUDO'], { NONE: 1 }, null]) {
    const c = ss.privilegeCapability({ sshPrivileges: v });
    assert.equal(c.sudo, false, JSON.stringify(v));
    assert.equal(c.supported, false);
  }
  const noneOnly = ss.privilegeCapability({ sshPrivileges: ['NONE'] });
  assert.deepEqual([noneOnly.supported, noneOnly.sudo], [true, false]);
  assert.equal(ss.normalizeServerList({ servers: [], sshPrivileges: ['NONE', 'SUDO'] }).sshPrivileges.length, 2);
});

test('請求本文：支援時明確送 privilege（預設 NONE、不擴權）；舊後端不送；未宣告 SUDO 不能選', () => {
  const none = ss.serverRequestBody(form(), { requestId: SID });
  assert.equal(none.privilege, 'NONE');
  const sudo = ss.serverRequestBody(form({ privilege: 'SUDO' }), { requestId: SID });
  assert.equal(sudo.privilege, 'SUDO');
  assert.ok(!('sudoPassword' in sudo) && !('password' in sudo));
  const old = ss.serverRequestBody(form({ privilegeCap: OLD }), { requestId: SID });
  assert.ok(!('privilege' in old), '舊後端保持舊契約');
  for (const cap of [OLD, ss.privilegeCapability({ sshPrivileges: ['NONE'] }), ss.privilegeCapability({ sshPrivileges: 'SUDO' })]) {
    assert.equal(ss.serverRequestBody(form({ privilege: 'SUDO', privilegeCap: cap }), { requestId: SID }), null);
    assert.ok(ss.serverFormCheck(form({ privilege: 'SUDO', privilegeCap: cap })).errors.some((e) => /免密 sudo/.test(e)));
  }
  assert.ok(ss.serverFormCheck(form({ privilege: undefined })).errors.length === 0, '未提供時視為直接執行');
  assert.ok(ss.serverFormCheck(form({ privilege: 'ROOT' })).errors.some((e) => /執行權限/.test(e)));
  assert.ok(ss.serverFormCheck(form({ privilege: '' })).errors.some((e) => /請選擇執行權限/.test(e)), '畫面未選擇時不悄悄當成直接執行');
  assert.notEqual(ss.serverIntentSignature(none), ss.serverIntentSignature(sudo), '改變權限是不同意圖');
});

test('伺服器檢視：省略為 NONE；未知值為 UNKNOWN 且不具權限；顯示實際使用者與方式', () => {
  const base = { id: SID, name: 'x', revision: 1, location: 'REMOTE', host: '203.0.113.40', port: 22, username: 'ubuntu', authentication: 'PASSWORD', hasCredential: true };
  assert.equal(ss.normalizeServer(base).privilege, 'NONE');
  assert.equal(ss.normalizeServer({ ...base, privilege: 'SUDO' }).privilege, 'SUDO');
  assert.equal(ss.normalizeServer({ ...base, privilege: 'sudo' }).privilege, 'UNKNOWN');
  assert.equal(ss.sshTarget(ss.normalizeServer({ ...base, privilege: 'SUDO' })), 'ubuntu@203.0.113.40:22（免密 sudo）');
  assert.equal(ss.sshTarget(ss.normalizeServer(base)), 'ubuntu@203.0.113.40:22');
  assert.match(ss.sshTarget(ss.normalizeServer({ ...base, privilege: 'X' })), /執行權限未知/);
});

test('root 前置條件：root 帳號，或後端宣告支援且選擇免密 sudo', () => {
  const srv = (username, privilege) => ss.normalizeServer({ id: SID, name: 'x', revision: 1, location: 'REMOTE', host: 'h.example', port: 22, username, authentication: 'PASSWORD', hasCredential: true, privilege });
  const full = { sshPrivileges: ['NONE', 'SUDO'] };
  assert.equal(ss.rootCapable(srv('root', 'NONE'), {}), true);
  assert.equal(ss.rootCapable(srv('ubuntu', 'SUDO'), full), true);
  assert.equal(ss.rootCapable(srv('ubuntu', 'SUDO'), {}), false, '舊後端不認 SUDO');
  assert.equal(ss.rootCapable(srv('ubuntu', 'NONE'), full), false);
  assert.equal(ss.rootCapable(srv('ubuntu', 'MOON'), full), false);
  const ext = (username, privilege, list) => ss.enrollmentAvailability(ss.normalizeServer({ id: SID, name: 'x', revision: 1, location: 'REMOTE', host: 'h.example', port: 22, username, privilege, authentication: 'PASSWORD', hasCredential: true, connection: { state: 'CONNECTED' },
    installation: { state: 'SUCCEEDED', mode: 'EXTERNAL', clusterId: SID, allocationSlot: 3, instanceId: SID, siteId: 'e', clusterMembership: 'NOT_JOINED' } }), { clusterEnrollmentEnabled: true, remoteMemberEnrollmentSupported: true, ...list });
  assert.deepEqual(ext('ubuntu', 'SUDO', full).reasons, []);
  assert.ok(ext('ubuntu', 'SUDO', {}).reasons.some((r) => /免密 sudo/.test(r)));
  assert.ok(ext('ubuntu', 'NONE', full).reasons.length);
  for (const code of ['FIRST_INSTALL_ROOT_REQUIRED', 'MEMBER_CONFIGURATION_ROOT_REQUIRED']) assert.match(ss.serverCodeText(code), /root[\s\S]*免密 sudo/);
  assert.match(ss.connectionChecks({ state: 'CONNECTED', root: false }).at(-1).detail, /root 或已配置免密 sudo/);
});

test('API：privilege 只接受 NONE／SUDO', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(201, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  const body = ss.serverRequestBody(form({ privilege: 'SUDO' }), { requestId: SID });
  await client.createServer(body);
  assert.equal(fetchImpl.calls[0].body.privilege, 'SUDO');
  for (const privilege of ['ROOT', '', null, 'sudo']) await assert.rejects(client.createServer({ ...body, privilege }), (e) => e.kind === 'validation', String(privilege));
  await assert.rejects(client.createServer({ ...body, sudoPassword: 'x' }), (e) => e.kind === 'validation');
  assert.equal(fetchImpl.calls.length, 1);
});

test('靜態：表單有執行權限選項；顯示改用 sshTarget；說明改為 root 或免密 sudo', () => {
  const html = read('index.html');
  assert.match(html, /<input type="radio" name="server-privilege" value="NONE" checked>/);
  assert.match(html, /name="server-privilege" value="SUDO"/);
  assert.match(html, /首次安裝需要 root，或下方選擇已配置的免密 sudo/);
  const ui = read('servers-ui.js');
  assert.ok(!ui.includes('${server.username}@${server.host}:${server.port}'), '全部改用 sshTarget');
  assert.match(ui, /setRadio\('server-privilege', server \? server\.privilege : 'NONE'\);/);
  assert.match(ui, /執行權限：\$\{ss\.PRIVILEGE_LABEL/);
});

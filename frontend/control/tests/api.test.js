import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiError, bearerValue, createControlClient, isValidHeaderName, login } from '../api.js';
import { createSession } from '../session.js';
import { fakeFetch, fakeSession, jsonResponse, UUID_A } from './helpers.js';

test('login：POST 同源 /auth/login，含 device WEB，不帶 cookie', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, { code: 0, data: { tokenName: 'satoken', tokenValue: 'tok-1' }, msg: 'ok' }));
  const result = await login({ username: 'admin', password: 'pw' }, { fetchImpl });
  assert.deepEqual(result, { tokenName: 'satoken', tokenValue: 'tok-1' });
  assert.equal(fetchImpl.calls.length, 1);
  const call = fetchImpl.calls[0];
  assert.equal(call.url, '/auth/login');
  assert.equal(call.init.method, 'POST');
  assert.equal(call.init.credentials, 'omit');
  assert.equal(call.init.cache, 'no-store');
  assert.deepEqual(call.body, { username: 'admin', password: 'pw', device: 'WEB' });
});

test('login：業務 code 200 不是成功（成功碼是 0）', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, { code: 200, data: { tokenName: 'satoken', tokenValue: 'x' }, msg: '' }));
  await assert.rejects(login({ username: 'a', password: 'b' }, { fetchImpl }), (err) => {
    assert.ok(err instanceof ApiError);
    assert.equal(err.kind, 'business');
    assert.equal(err.code, '200');
    return true;
  });
});

test('login：業務失敗回傳 msg 但錯誤不含密碼', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, { code: 40001, msg: '帳號或密碼錯誤' }));
  await assert.rejects(login({ username: 'a', password: 'secret-pw' }, { fetchImpl }), (err) => {
    assert.equal(err.serverMessage, '帳號或密碼錯誤');
    assert.ok(!JSON.stringify(err).includes('secret-pw'));
    assert.ok(!String(err.message).includes('secret-pw'));
    return true;
  });
});

test('login：缺 tokenName 或 tokenName 不是合法 header 名稱 → protocol 錯誤', async () => {
  for (const data of [{ tokenValue: 'x' }, { tokenName: 'bad name', tokenValue: 'x' }, { tokenName: 'satoken', tokenValue: '' }, { tokenName: 'Cookie', tokenValue: 'x' }]) {
    const fetchImpl = fakeFetch(() => jsonResponse(200, { code: 0, data }));
    await assert.rejects(login({ username: 'a', password: 'b' }, { fetchImpl }), (err) => err.kind === 'protocol');
  }
});

test('login：空帳密不送出請求', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  await assert.rejects(login({ username: '', password: 'x' }, { fetchImpl }), (err) => err.kind === 'validation');
  assert.equal(fetchImpl.calls.length, 0);
});

test('bearerValue：補上 Bearer 前綴且不重複', () => {
  assert.equal(bearerValue('abc'), 'Bearer abc');
  assert.equal(bearerValue('Bearer abc'), 'Bearer abc');
  assert.equal(bearerValue('bearer   abc'), 'Bearer abc');
  assert.throws(() => bearerValue(''));
  assert.throws(() => bearerValue('Bearer '));
  assert.throws(() => bearerValue('a b'));
  assert.throws(() => bearerValue('abc\r\nX-Evil: 1'));
});

test('isValidHeaderName', () => {
  assert.equal(isValidHeaderName('satoken'), true);
  assert.equal(isValidHeaderName('Authorization'), true);
  assert.equal(isValidHeaderName('x y'), false);
  assert.equal(isValidHeaderName('content-type'), false);
  assert.equal(isValidHeaderName(''), false);
  assert.equal(isValidHeaderName(undefined), false);
});

test('控制 API：固定基底、使用登入回傳的 header 名稱、直接回傳 JSON 不解包', async () => {
  const payload = { observedAt: '2026-10-16T00:00:00Z', pairStatus: 'UNVERIFIED', sites: {} };
  const fetchImpl = fakeFetch(() => jsonResponse(200, payload));
  const session = createSession();
  session.set('my-token', 'Bearer tv');
  const client = createControlClient({ session, fetchImpl });
  const data = await client.getSites();
  assert.deepEqual(data, payload);
  const call = fetchImpl.calls[0];
  assert.equal(call.url, '/admin/control/v1/sites');
  assert.equal(call.init.headers['my-token'], 'Bearer tv');
  assert.equal(call.init.credentials, 'omit');
  assert.equal(call.init.redirect, 'error');
  assert.ok(!call.url.includes('tv'), '權杖不可出現在 URL');
});

test('控制 API：業務格式的 {code,data} 也不會被解包', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, { code: 0, data: { a: 1 } }));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  assert.deepEqual(await client.getCapabilities(), { code: 0, data: { a: 1 } });
});

test('控制 API：錯誤回應解析 code 與 HTTP 狀態', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(403, { code: 'ADMIN_REQUIRED', timestamp: '2026-10-16T00:00:00Z' }));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await assert.rejects(client.getSites(), (err) => {
    assert.equal(err.kind, 'http');
    assert.equal(err.status, 403);
    assert.equal(err.code, 'ADMIN_REQUIRED');
    assert.equal(err.timestamp, '2026-10-16T00:00:00Z');
    return true;
  });
});

test('控制 API：非 JSON 錯誤頁仍得到 HTTP 狀態', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(502, '<html>bad gateway</html>'));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await assert.rejects(client.getSites(), (err) => err.kind === 'http' && err.status === 502 && err.code === null);
});

test('控制 API：成功但非 JSON → parse 錯誤（寫入時視為結果未知）', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, 'not json'));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await assert.rejects(client.getSites(), (err) => err.kind === 'parse' && err.outcomeUnknown === true);
});

test('控制 API：網路錯誤 → network，且 outcomeUnknown', async () => {
  const fetchImpl = async () => {
    throw new TypeError('Failed to fetch');
  };
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await assert.rejects(client.getSites(), (err) => err.kind === 'network' && err.outcomeUnknown);
});

test('控制 API：逾時 → timeout', async () => {
  const fetchImpl = (url, init) =>
    new Promise((_, reject) => {
      init.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
    });
  const client = createControlClient({ session: fakeSession(), fetchImpl, timeoutMs: 20 });
  await assert.rejects(client.getSites(), (err) => err.kind === 'timeout');
});

test('控制 API：NODE_REQUEST_FAILED 視為結果未知', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(503, { code: 'NODE_REQUEST_FAILED' }));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await assert.rejects(client.postFault('campus', { channel: 'events', action: 'RESTORE', seconds: 10 }), (err) => err.outcomeUnknown);
});

test('控制 API：未登入不發出請求', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: createSession(), fetchImpl });
  await assert.rejects(client.getSites(), (err) => err.kind === 'auth' && err.status === 401);
  assert.equal(fetchImpl.calls.length, 0);
});

test('postFault：合法請求只送出 channel/action/seconds', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, { requestId: UUID_A, channel: { state: 'BLOCKED' } }));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.postFault('external', { channel: 'events', action: 'BLOCK', seconds: '30', extra: 'x' });
  const call = fetchImpl.calls[0];
  assert.equal(call.url, '/admin/control/v1/sites/external/faults');
  assert.equal(call.init.method, 'POST');
  assert.equal(call.init.headers['Content-Type'], 'application/json');
  assert.deepEqual(call.body, { channel: 'events', action: 'BLOCK', seconds: 30 });
});

test('postFault：不合法輸入不送出', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  const bad = [
    ['other', { channel: 'events', action: 'BLOCK', seconds: 30 }],
    ['../campus', { channel: 'events', action: 'BLOCK', seconds: 30 }],
    ['campus', { channel: '../x', action: 'BLOCK', seconds: 30 }],
    ['campus', { channel: 'events', action: 'DROP', seconds: 30 }],
    ['campus', { channel: 'events', action: 'BLOCK', seconds: 0 }],
    ['campus', { channel: 'events', action: 'BLOCK', seconds: 301 }],
    ['campus', { channel: 'events', action: 'RESTORE' }],
  ];
  for (const [site, req] of bad) {
    await assert.rejects(client.postFault(site, req), (err) => err.kind === 'validation');
  }
  assert.equal(fetchImpl.calls.length, 0);
});

test('任務：只接受固定套件與 UUID', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(202, { id: UUID_A, state: 'QUEUED' }));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.startTestRun('business');
  assert.deepEqual(fetchImpl.calls[0].body, { suite: 'business' });
  await assert.rejects(client.startTestRun('mvn -X'), (err) => err.kind === 'validation');
  await assert.rejects(client.getTestRun('../../etc'), (err) => err.kind === 'validation');
  await assert.rejects(client.cancelTestRun('abc'), (err) => err.kind === 'validation');
  await client.cancelTestRun(UUID_A);
  assert.equal(fetchImpl.calls.at(-1).url, `/admin/control/v1/test-runs/${UUID_A}/cancel`);
  assert.equal(fetchImpl.calls.length, 2);
});

test('部署：預覽及執行只接受已登記 ID', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(201, { id: UUID_A, state: 'PREVIEW_ONLY', executionEnabled: false }));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.createDeploymentPlan({ releaseId: 'candidate-1', sites: ['external', 'campus', 'campus'] });
  assert.equal(fetchImpl.calls[0].url, '/admin/control/v1/deployment-plans');
  assert.deepEqual(fetchImpl.calls[0].body, { releaseId: 'candidate-1', sites: ['campus', 'external'] });
  await assert.rejects(client.createDeploymentPlan({ releaseId: '/opt/x.jar', sites: ['campus'] }), (err) => err.kind === 'validation');
  await assert.rejects(client.createDeploymentPlan({ releaseId: 'candidate-1', sites: [] }), (err) => err.kind === 'validation');
  const names = Object.keys(client);
  assert.ok(names.includes('executeDeployment'));
  await assert.rejects(client.executeDeployment('../../etc'), (err) => err.kind === 'validation');
  await client.executeDeployment(UUID_A);
  assert.equal(fetchImpl.calls.at(-1).url, `/admin/control/v1/deployment-plans/${UUID_A}/execute`);
  assert.equal(fetchImpl.calls.at(-1).options?.body, undefined);
});

test('稽核：limit 必須為 1–200 整數', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, []));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.getAudit(50);
  assert.equal(fetchImpl.calls[0].url, '/admin/control/v1/audit?limit=50');
  for (const bad of [0, 201, 1.5, '50', NaN]) {
    await assert.rejects(client.getAudit(bad), (err) => err.kind === 'validation');
  }
  assert.equal(fetchImpl.calls.length, 1);
});

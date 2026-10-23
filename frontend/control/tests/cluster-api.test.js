import test from 'node:test';
import assert from 'node:assert/strict';

import { createClusterClient } from '../api.js';
import { CLUSTER_BASE, OBSERVATION_TIMEOUT_MS, REQUEST_TIMEOUT_MS } from '../constants.js';
import { fakeFetch, fakeSession, jsonResponse, UUID_A, UUID_B } from './helpers.js';

function client(responder = () => jsonResponse(200, {}), extra = {}) {
  const fetchImpl = fakeFetch(responder);
  return { fetchImpl, api: createClusterClient({ session: fakeSession(), fetchImpl, ...extra }) };
}

test('cluster：固定基底與登入回傳的 header，不帶 cookie', async () => {
  const { fetchImpl, api } = client(() => jsonResponse(200, { clusterId: UUID_A, epoch: 3, members: [], pendingChanges: [], worker: { running: true, lastCode: 'OK' } }));
  const data = await api.getStatus();
  assert.equal(data.epoch, 3);
  const call = fetchImpl.calls[0];
  assert.equal(call.url, CLUSTER_BASE);
  assert.equal(call.url, '/admin/control/v1/cluster');
  assert.equal(call.init.headers.satoken, 'Bearer abc123');
  assert.equal(call.init.credentials, 'omit');
  assert.equal(call.init.redirect, 'error');
});

test('cluster：讀取接口路徑與查詢參數', async () => {
  const { fetchImpl, api } = client(() => jsonResponse(200, []));
  await api.getCapabilities();
  await api.getProfiles();
  await api.getObservations();
  await api.getChannels();
  await api.listChanges(20);
  await api.listGc({ limit: 20, pendingOnly: true });
  await api.listGc({ limit: 5, pendingOnly: false });
  await api.listGcCandidates(20);
  await api.getAudit(50);
  await api.getChange(UUID_A);
  await api.getGc(UUID_B);
  await api.getPreview(UUID_A);
  assert.deepEqual(
    fetchImpl.calls.map((c) => c.url.replace(CLUSTER_BASE, '')),
    [
      '/capabilities',
      '/profiles',
      '/observations',
      '/channels',
      '/changes?limit=20',
      '/gc?limit=20&pendingOnly=true',
      '/gc?limit=5&pendingOnly=false',
      '/gc/candidates?limit=20',
      '/audit?limit=50',
      `/changes/${UUID_A}`,
      `/gc/${UUID_B}`,
      `/deployment-plans/${UUID_A}`,
    ],
  );
  assert.ok(fetchImpl.calls.every((c) => c.init.method === 'GET'));
});

test('cluster：limit 必須 1–100，pendingOnly 必須是布林值', async () => {
  const { fetchImpl, api } = client();
  for (const bad of [0, 101, 1.5, '20', undefined]) {
    await assert.rejects(api.listChanges(bad), (e) => e.kind === 'validation');
    await assert.rejects(api.getAudit(bad), (e) => e.kind === 'validation');
    await assert.rejects(api.listGcCandidates(bad), (e) => e.kind === 'validation');
  }
  await assert.rejects(api.listGc({ limit: 20, pendingOnly: 'true' }), (e) => e.kind === 'validation');
  assert.equal(fetchImpl.calls.length, 0);
});

test('cluster：成員變更請求只送 requestId／expectedEpoch（＋profileId）', async () => {
  const { fetchImpl, api } = client(() => jsonResponse(202, {}));
  await api.registerSite({ requestId: UUID_A, expectedEpoch: 0, profileId: 'site-3', extra: 'x' });
  await api.joinSite('site-3', { requestId: UUID_A, expectedEpoch: 4, force: true });
  await api.retireSite('site-3', { requestId: UUID_B, expectedEpoch: 5 });
  await api.cancelSite('site-3', { requestId: UUID_B, expectedEpoch: 5 });
  const [reg, join, retire, cancel] = fetchImpl.calls;
  assert.equal(reg.url, `${CLUSTER_BASE}/sites`);
  assert.deepEqual(reg.body, { requestId: UUID_A, expectedEpoch: 0, profileId: 'site-3' });
  assert.equal(join.url, `${CLUSTER_BASE}/sites/site-3/join`);
  assert.deepEqual(join.body, { requestId: UUID_A, expectedEpoch: 4 }, '不轉送 force 等額外欄位');
  assert.equal(retire.url, `${CLUSTER_BASE}/sites/site-3/retire`);
  assert.equal(cancel.url, `${CLUSTER_BASE}/sites/site-3/cancel`);
  assert.ok(fetchImpl.calls.every((c) => c.init.method === 'POST'));
});

test('cluster：成員變更輸入驗證', async () => {
  const { fetchImpl, api } = client();
  await assert.rejects(api.joinSite('../x', { requestId: UUID_A, expectedEpoch: 1 }), (e) => e.kind === 'validation');
  await assert.rejects(api.joinSite('a b', { requestId: UUID_A, expectedEpoch: 1 }), (e) => e.kind === 'validation');
  await assert.rejects(api.joinSite('site-3', { requestId: 'x', expectedEpoch: 1 }), (e) => e.kind === 'validation');
  await assert.rejects(api.joinSite('site-3', { requestId: UUID_A, expectedEpoch: -1 }), (e) => e.kind === 'validation');
  await assert.rejects(api.joinSite('site-3', { requestId: UUID_A, expectedEpoch: 1.5 }), (e) => e.kind === 'validation');
  await assert.rejects(api.registerSite({ requestId: UUID_A, expectedEpoch: 0, profileId: '/etc/x' }), (e) => e.kind === 'validation');
  await assert.rejects(api.resumeChange('nope'), (e) => e.kind === 'validation');
  assert.equal(fetchImpl.calls.length, 0);
});

test('cluster：resume 不帶 body', async () => {
  const { fetchImpl, api } = client(() => jsonResponse(200, {}));
  await api.resumeChange(UUID_A);
  await api.resumeGc(UUID_B);
  assert.equal(fetchImpl.calls[0].url, `${CLUSTER_BASE}/changes/${UUID_A}/resume`);
  assert.equal(fetchImpl.calls[0].body, undefined);
  assert.equal(fetchImpl.calls[1].url, `${CLUSTER_BASE}/gc/${UUID_B}/resume`);
  assert.equal(fetchImpl.calls[1].body, undefined);
  await api.reloadProfiles();
  assert.equal(fetchImpl.calls[2].url, `${CLUSTER_BASE}/profiles/reload`);
  assert.equal(fetchImpl.calls[2].body, undefined, 'reload 不傳任何路徑');
});

test('cluster：故障只送 channel／action／seconds', async () => {
  const { fetchImpl, api } = client(() => jsonResponse(200, {}));
  await api.postFault('site-2', { channel: 'events', action: 'BLOCK', seconds: '30', requestId: 'x' });
  assert.equal(fetchImpl.calls[0].url, `${CLUSTER_BASE}/sites/site-2/faults`);
  assert.deepEqual(fetchImpl.calls[0].body, { channel: 'events', action: 'BLOCK', seconds: 30 });
  await assert.rejects(api.postFault('site-2', { channel: 'events', action: 'BLOCK', seconds: 301 }), (e) => e.kind === 'validation');
  await assert.rejects(api.postFault('site-2', { channel: 'events', action: 'DROP', seconds: 1 }), (e) => e.kind === 'validation');
  assert.equal(fetchImpl.calls.length, 1);
});

test('cluster：GC 發起只接受 operationId／fileId／revision／expectedEpoch', async () => {
  const { fetchImpl, api } = client(() => jsonResponse(202, {}));
  await api.beginGc({ operationId: UUID_A, fileId: UUID_B, revision: 2, expectedEpoch: 3, key: 'objects/x', bucket: 'b', skip: true });
  assert.equal(fetchImpl.calls[0].url, `${CLUSTER_BASE}/gc`);
  assert.deepEqual(fetchImpl.calls[0].body, { operationId: UUID_A, fileId: UUID_B, revision: 2, expectedEpoch: 3 });
  for (const bad of [
    { operationId: 'x', fileId: UUID_B, revision: 1, expectedEpoch: 1 },
    { operationId: UUID_A, fileId: UUID_B, revision: 0, expectedEpoch: 1 },
    { operationId: UUID_A, fileId: UUID_B, revision: 1, expectedEpoch: 0 },
  ]) {
    await assert.rejects(api.beginGc(bad), (e) => e.kind === 'validation');
  }
  assert.equal(fetchImpl.calls.length, 1);
});

test('cluster：部署預覽驗證 releaseId 格式與站點數，執行只接受計畫 UUID', async () => {
  const { fetchImpl, api } = client(() => jsonResponse(201, {}));
  await api.createPreview({ requestId: UUID_A, expectedEpoch: 2, releaseId: 'candidate-1', sites: ['campus', 'site-3'] });
  assert.equal(fetchImpl.calls[0].url, `${CLUSTER_BASE}/deployment-plans`);
  assert.deepEqual(fetchImpl.calls[0].body, { requestId: UUID_A, expectedEpoch: 2, releaseId: 'candidate-1', sites: ['campus', 'site-3'] });
  await assert.rejects(api.createPreview({ requestId: UUID_A, expectedEpoch: 2, releaseId: 'v1.2', sites: ['campus'] }), (e) => e.kind === 'validation', '含點的 ID 不改寫、直接拒絕');
  await assert.rejects(api.createPreview({ requestId: UUID_A, expectedEpoch: 0, releaseId: 'r', sites: ['campus'] }), (e) => e.kind === 'validation');
  await assert.rejects(api.createPreview({ requestId: UUID_A, expectedEpoch: 2, releaseId: 'r', sites: [] }), (e) => e.kind === 'validation');
  await assert.rejects(api.createPreview({ requestId: UUID_A, expectedEpoch: 2, releaseId: 'r', sites: ['a', 'a'] }), (e) => e.kind === 'validation');
  const many = Array.from({ length: 17 }, (_, i) => `s${i}`);
  await assert.rejects(api.createPreview({ requestId: UUID_A, expectedEpoch: 2, releaseId: 'r', sites: many }), (e) => e.kind === 'validation');
  await assert.rejects(api.executeDeployment('../etc'), (e) => e.kind === 'validation');
  await api.executeDeployment(UUID_A);
  assert.equal(fetchImpl.calls.at(-1).url, `${CLUSTER_BASE}/deployment-plans/${UUID_A}/execute`);
  const names = Object.keys(api);
  assert.ok(!names.some((n) => /consent|force|override|waive/i.test(n)), names.join(','));
});

test('cluster：observations 使用獨立逾時（預設 360 秒），其他接口仍為短逾時', () => {
  assert.equal(OBSERVATION_TIMEOUT_MS, 360000);
  const { api } = client();
  assert.equal(api.observationTimeoutMs, 360000);
  assert.notEqual(REQUEST_TIMEOUT_MS, OBSERVATION_TIMEOUT_MS);
});

test('cluster：observations 逾時可調；短接口逾時不影響觀測', async () => {
  const hang = (url, init) =>
    new Promise((resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
      if (!url.endsWith('/observations')) setTimeout(() => resolve(jsonResponse(200, {})), 5);
    });
  const api = createClusterClient({ session: fakeSession(), fetchImpl: hang, timeoutMs: 10, observationTimeoutMs: 30 });
  const t0 = Date.now();
  await assert.rejects(api.getObservations(), (e) => e.kind === 'timeout');
  assert.ok(Date.now() - t0 >= 25, '使用 observationTimeoutMs 而非短逾時');
});

test('cluster：外部 signal 中止 → aborted（登出），且視為結果未知', async () => {
  const fetchImpl = (url, init) =>
    new Promise((_, reject) => {
      init.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
    });
  const api = createClusterClient({ session: fakeSession(), fetchImpl, observationTimeoutMs: 60000 });
  const ctrl = new AbortController();
  const p = api.getObservations({ signal: ctrl.signal });
  ctrl.abort();
  await assert.rejects(p, (e) => e.kind === 'aborted' && e.outcomeUnknown === true);
});

test('cluster：已中止的 signal 立即中止', async () => {
  const fetchImpl = (url, init) =>
    new Promise((_, reject) => {
      if (init.signal.aborted) reject(Object.assign(new Error('aborted'), { name: 'AbortError' }));
    });
  const api = createClusterClient({ session: fakeSession(), fetchImpl });
  const ctrl = new AbortController();
  ctrl.abort();
  await assert.rejects(api.getStatus({ signal: ctrl.signal }), (e) => e.kind === 'aborted');
});

test('cluster：capabilities 404 保持為 http 錯誤，只發一次請求、不改呼叫舊 capabilities', async () => {
  const { fetchImpl, api } = client(() => jsonResponse(404, { code: 'NOT_FOUND' }));
  await assert.rejects(api.getCapabilities(), (e) => {
    assert.equal(e.kind, 'http');
    assert.equal(e.status, 404);
    assert.equal(e.outcomeUnknown, false);
    return true;
  });
  assert.equal(fetchImpl.calls.length, 1);
  assert.equal(fetchImpl.calls[0].url, `${CLUSTER_BASE}/capabilities`);
  assert.ok(!fetchImpl.calls.some((c) => c.url === '/admin/control/v1/capabilities'), '不呼叫舊 capabilities');
});

test('cluster：入口判定只拒絕 401／403／404；503、逾時、網路錯誤仍進入並標示未知', async () => {
  const { isEntryRejection } = await import('../cluster-state.js');
  for (const status of [401, 403, 404]) {
    const { api } = client(() => jsonResponse(status, { code: 'X' }));
    await assert.rejects(api.getCapabilities(), (e) => isEntryRejection(e) === true);
  }
  const { api: api503 } = client(() => jsonResponse(503, { code: 'NODE_UNAVAILABLE' }));
  await assert.rejects(api503.getCapabilities(), (e) => isEntryRejection(e) === false);
  const netApi = createClusterClient({ session: fakeSession(), fetchImpl: async () => { throw new TypeError('Failed to fetch'); } });
  await assert.rejects(netApi.getCapabilities(), (e) => e.kind === 'network' && isEntryRejection(e) === false);
  assert.equal(isEntryRejection({ kind: 'timeout', status: 0 }), false);
  assert.equal(isEntryRejection({ kind: 'validation', status: 404 }), false, '非 http 錯誤不視為入口拒絕');
  assert.equal(isEntryRejection(null), false);
});

test('cluster：穩定錯誤碼解析（409 STALE_MEMBERSHIP_EPOCH、503）', async () => {
  const { api } = client(() => jsonResponse(409, { code: 'STALE_MEMBERSHIP_EPOCH' }));
  await assert.rejects(api.joinSite('s', { requestId: UUID_A, expectedEpoch: 1 }), (e) => e.status === 409 && e.code === 'STALE_MEMBERSHIP_EPOCH' && !e.outcomeUnknown);
  const { api: api503 } = client(() => jsonResponse(503, { code: 'NODE_UNAVAILABLE' }));
  await assert.rejects(api503.getObservations(), (e) => e.status === 503);
});

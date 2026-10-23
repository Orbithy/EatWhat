import test from 'node:test';
import assert from 'node:assert/strict';
import { readResources } from '../read-resources.js';

test('fresh cluster reads are delivered while a slow audit is still pending', async () => {
  let finishStatus, finishAudit;
  const status = new Promise((resolve) => { finishStatus = resolve; });
  const audit = new Promise((resolve) => { finishAudit = resolve; });
  let clock = 1000;
  const delivered = [];
  const polling = readResources({ status: () => status, audit: () => audit }, () => clock,
    (key, result, started, received) => delivered.push({ key, result, started, received }));
  clock = 9000;
  finishStatus({ epoch: 3 });
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(delivered, [{ key: 'status', result: { status: 'fulfilled', value: { epoch: 3 } }, started: 1000, received: 9000 }]);
  clock = 31000;
  finishAudit([]);
  await polling;
  assert.equal(delivered[0].started, 1000, 'late audit must not make old status appear fresh');
  assert.equal(delivered[1].received, 31000);
});

test('one failed read still delivers other resource results', async () => {
  const failure = new Error('timeout');
  const delivered = new Map();
  await readResources({ status: () => { throw failure; }, leadership: async () => ({ generation: 5 }) }, () => 0,
    (key, result) => delivered.set(key, result));
  assert.deepEqual(delivered.get('status'), { status: 'rejected', reason: failure });
  assert.deepEqual(delivered.get('leadership'), { status: 'fulfilled', value: { generation: 5 } });
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { executionReasons } from '../deployment-state.js';
const caps = { deploymentExecutionEnabled: true };
const plan = { id: '11111111-1111-4111-8111-111111111111', state: 'PREVIEW_ONLY', artifactSha256: 'a'.repeat(64), executionEnabled: true, blockers: [] };
test('execution requires explicit capability, valid immutable artifact and zero blockers', () => {
  assert.deepEqual(executionReasons(caps, plan), []);
  for (const bad of [null, {}, { ...caps, deploymentExecutionEnabled: 'true' }]) assert.ok(executionReasons(bad, plan).length);
  for (const bad of [null, {}, { ...plan, executionEnabled: false }, { ...plan, artifactSha256: 'invalid' }, { ...plan, id: '../etc' }, { ...plan, blockers: undefined }, { ...plan, blockers: ['RESTORE_REHEARSAL_NOT_REVIEWED'] }, { ...plan, state: 'RUNNING' }]) assert.ok(executionReasons(caps, bad).length);
});
test('membership epoch drift or instance replacement blocks deployment', () => {
  assert.ok(executionReasons(caps, { ...plan, epochStale: true }).length);
  assert.ok(executionReasons(caps, { ...plan, memberChanges: [{ siteId: 'campus' }] }).length);
});

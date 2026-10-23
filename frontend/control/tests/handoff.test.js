import test from 'node:test';
import assert from 'node:assert/strict';
import { handoffContext } from '../handoff-state.js';
import { leadershipWriteReasons, normalizeLeadership } from '../cluster-state.js';
import { createClusterClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse, UUID_A, UUID_B } from './helpers.js';

const raw = () => ({ state: 'HELD', localLeader: false, leaderControllerId: UUID_A, generation: 4, handoffEnabled: true,
  handoff: { state: 'NONE' }, controllers: [{ id: UUID_A, name: 'leader', sharedState: true }, { id: UUID_B, name: 'standby', sharedState: true }] });
test('standby administrators can request handoff only to a live, shared-state candidate', () => {
  assert.equal(handoffContext(raw(), true, true).canRequest, true);
  assert.deepEqual(handoffContext(raw(), true, true).candidates.map((x) => x.id), [UUID_B]);
  for (const [fresh, enabled] of [[false, true], [true, false], [true, 'true']]) assert.equal(handoffContext(raw(), fresh, enabled).canRequest, false);
  const data = raw();data.controllers[1].sharedState = 'true';assert.equal(handoffContext(data, true, true).canRequest, false);
  data.controllers[1].sharedState = false;assert.equal(handoffContext(data, true, true).canRequest, false);
});
test('waiting permits cancellation; transferring and unknown states prohibit new handoffs', () => {
  const data = raw();data.handoff = { id: UUID_A, state: 'WAITING', reason: 'DEPLOYMENT_RUNNING' };
  let view = handoffContext(data, true, true);assert.equal(view.canRequest, false);assert.equal(view.canCancel, true);assert.match(view.reason, /部署/);
  data.handoff.state = 'TRANSFERRING';view = handoffContext(data, true, true);assert.equal(view.canCancel, false);assert.equal(view.canRequest, false);
  data.handoff.state = 'FUTURE_STATE';assert.equal(handoffContext(data, true, true).valid, false);
  data.handoff.state = 'COMPLETED';assert.equal(handoffContext(data, true, true).canRequest, true);
});
test('a draining leader stops new control writes while its current deployment keeps running', () => {
  const data = raw();data.localLeader = true;data.handoff = { state: 'WAITING' };
  assert.ok(leadershipWriteReasons(normalizeLeadership(data)).length);
  data.handoff.state = 'COMPLETED';assert.deepEqual(leadershipWriteReasons(normalizeLeadership(data)), []);
});
test('handoff APIs submit only UUIDs and the frozen generation, including lookup and cancellation', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(202, { id: UUID_A, state: 'WAITING' }));
  const api = createClusterClient({ session: fakeSession(), fetchImpl });
  await api.requestHandoff({ requestId: UUID_A, targetControllerId: UUID_B, expectedGeneration: 4, host: 'ignored.invalid' });
  assert.deepEqual(fetchImpl.calls[0].body, { requestId: UUID_A, targetControllerId: UUID_B, expectedGeneration: 4 });
  assert.ok(fetchImpl.calls[0].url.endsWith('/leadership/handoffs'));
  await api.getHandoff(UUID_A);assert.equal(fetchImpl.calls[1].init.method, 'GET');
  await api.cancelHandoff(UUID_A);assert.ok(fetchImpl.calls[2].url.endsWith('/cancel'));assert.equal(fetchImpl.calls[2].body, undefined);
  for (const bad of [{ expectedGeneration: 0 }, { targetControllerId: '../bad' }, { requestId: 'not-a-uuid' }]) {
    await assert.rejects(api.requestHandoff({ requestId: UUID_A, targetControllerId: UUID_B, expectedGeneration: 4, ...bad }));
  }
  assert.equal(fetchImpl.calls.length, 3);
});

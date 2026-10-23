import { isUuid } from './validate.js';

const STATES = new Set(['NONE', 'WAITING', 'TRANSFERRING', 'COMPLETED', 'CANCELLED', 'FAILED']);
export const HANDOFF_REASONS = {
  WAITING_FOR_QUIESCENCE: '等待目前工作結束', DEPLOYMENT_RUNNING: '等待目前部署完成',
  DEPLOYMENT_RECONCILIATION_REQUIRED: '部署結果未知，請先核對遠端部署紀錄',
  CONTROL_CYCLE_RUNNING: '等待目前協調工作結束', CONTROL_TARGET_OFFLINE: '接管中控目前離線',
  CONTROL_SHARED_STATE_REQUIRED: '兩邊未使用相同的共享部署狀態', WAITING_FOR_TARGET_ACK: '已交出租約，等待指定中控確認',
  TARGET_ACKNOWLEDGED: '指定中控已接管', ADMIN_CANCELLED: '管理員已取消',
  SOURCE_LEASE_CHANGED: '原主租約已改變，請重新確認', TARGET_TAKEOVER_NOT_CONFIRMED: '指定中控未完成接管',
};
export function handoffContext(raw, fresh, enabled) {
  const current = raw?.handoff;
  const state = STATES.has(current?.state) ? current.state : 'UNKNOWN';
  const pending = ['WAITING', 'TRANSFERRING'].includes(state);
  const valid = enabled === true && fresh === true && raw?.handoffEnabled === true && raw.state === 'HELD'
    && isUuid(raw.leaderControllerId) && Number.isSafeInteger(raw.generation) && raw.generation >= 1 && state !== 'UNKNOWN';
  const seen = new Set();
  const candidates = (Array.isArray(raw?.controllers) ? raw.controllers : []).filter((entry) => {
    if (!isUuid(entry?.id) || seen.has(entry.id) || entry.sharedState !== true || entry.id === raw.leaderControllerId) return false;
    seen.add(entry.id); return true;
  });
  return { valid, state, pending, current, candidates, generation: raw?.generation,
    canRequest: valid && !pending && candidates.length > 0,
    canCancel: valid && state === 'WAITING' && isUuid(current?.id),
    reason: !fresh ? '領導狀態未更新，請重新讀取。' : !valid ? '目前入口未啟用可用的主備交接。'
      : pending ? (HANDOFF_REASONS[current.reason] ?? current.reason ?? state)
        : candidates.length === 0 ? '尚無可接管的在線備用中控；請確認備用進程已啟動且狀態同步正常。' : '可選擇在線備用中控。',
  };
}

// 提交／查回的交接回應與正式 leadership.handoff 合併：只有「最新觀測」中「同一 UUID」且狀態「更前進」時才同步；
// 不同 UUID、觀測過期、讀取失敗、未知狀態一律保留原回應，不推測完成。FAILED／CANCELLED 照實顯示，不說成完成。
const PROGRESS = { WAITING: 1, TRANSFERRING: 2, COMPLETED: 3, CANCELLED: 3, FAILED: 3 };
export function handoffResultView(submitted, raw, fresh) {
  if (!submitted || !isUuid(submitted.id)) return null;
  const official = raw?.handoff;
  const synced = fresh === true && official?.id === submitted.id && STATES.has(official.state)
    && (PROGRESS[official.state] ?? 0) > (PROGRESS[submitted.state] ?? 0);
  const state = synced ? official.state : submitted.state;
  const terminal = PROGRESS[state] === 3;
  const reason = synced && typeof official.reason === 'string' && official.reason ? (HANDOFF_REASONS[official.reason] ?? official.reason) : null;
  return {
    id: submitted.id, state, synced, terminal,
    text: `交接 ${submitted.id} · ${state}${synced ? `（已依最新領導狀態更新${reason ? `：${reason}` : ''}）` : ''}。${terminal ? '' : '狀態會持續更新。'}`,
  };
}

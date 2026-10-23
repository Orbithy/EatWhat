import { UNRESOLVED_CODES } from './tasks-state.js';

// 權威發布約束尚未收斂或接受結果未知時，任務即使回報 SUCCEEDED 也視為結果未知（需核對）。
export function effectiveJobState(job) {
  if (!job) return null;
  if (UNRESOLVED_CODES.includes(job.code) || UNRESOLVED_CODES.includes(job.reason)) return 'OUTCOME_UNKNOWN';
  return job.state;
}

// Missing/unknown capability and blocker fields fail closed.
export function executionReasons(capabilities, plan) {
  const reasons = [];
  if (capabilities?.deploymentExecutionEnabled !== true) reasons.push('服務端尚未啟用部署執行器。');
  if (!plan) { reasons.push('請先建立部署計畫。'); return reasons; }
  if (!/^[0-9a-f-]{36}$/i.test(plan.id ?? '')) reasons.push('計畫 UUID 無效。');
  if (plan.executionEnabled !== true) reasons.push('這份計畫尚未滿足部署條件。');
  if (plan.state !== 'PREVIEW_ONLY') reasons.push('計畫狀態不允許開始新部署。');
  if (!/^[0-9a-f]{64}$/.test(plan.artifactSha256 ?? '')) reasons.push('制品 SHA-256 無效。');
  if (!Array.isArray(plan.blockers)) reasons.push('服務端未提供阻擋項。');
  for (const item of plan.blockers ?? []) reasons.push(`阻擋項：${typeof item === 'string' ? item : item.title ?? item.code ?? '未知'}`);
  if (plan.epochStale) reasons.push('計畫的成員 epoch 已過期。');
  if (plan.memberChanges?.length) reasons.push('計畫中的成員身份已改變。');
  return reasons;
}

// 建立預覽確認框：這次操作本身只建立 PREVIEW_ONLY 預覽、不部署；執行能力只依正式 capabilities 說明。
// 只有讀取成功且 deploymentExecutionEnabled 嚴格 true 才說「可另行執行」；false 說未啟用；缺少、異常值或讀取失敗一律未知。
export function previewCreateExecutionLine(capabilities, { error = null } = {}) {
  const head = '這一步只建立 PREVIEW_ONLY 預覽，不會部署。';
  const enabled = error ? undefined : capabilities?.deploymentExecutionEnabled;
  if (enabled === true) return `${head}服務端已啟用部署執行器：預覽建立後若服務端回報可執行且沒有阻擋，需在下一個確認框另行確認才會部署。`;
  if (enabled === false) return `${head}服務端未啟用部署執行器，這份預覽不能執行部署。`;
  return `${head}部署執行能力未知（未取得或回報異常），不能確認之後可否執行。`;
}

// 預覽建立後的結果說明：以 capabilities 與預覽回應（executionEnabled、阻擋項等）共同判斷，沿用 executionReasons 的失敗即關閉規則。
export function previewResultExecutionLine(capabilities, plan, { error = null } = {}) {
  const reasons = executionReasons(error ? null : capabilities, plan);
  if (!reasons.length) return '服務端回報這份預覽可執行；尚未部署，需在下一個確認框另行確認。';
  return `目前不能執行：${reasons[0]}${reasons.length > 1 ? `（另有 ${reasons.length - 1} 項）` : ''}`;
}

// 能力區塊顯示：依正式 deploymentExecutionEnabled 如實呈現；只有嚴格 true 才是已啟用，缺失／非布林為未知。
// 已啟用也不代表可直接部署：仍需預覽無阻擋並在確認框另行確認（實際判斷見 executionReasons）。
export function executionCapabilityView(value) {
  if (value === true) return { state: 'ENABLED', tone: 'teal', badge: '部署執行器已啟用', detail: 'true · 已啟用；每次部署仍需預覽無阻擋並在確認框另行確認' };
  if (value === false) return { state: 'DISABLED', tone: 'outline', badge: '部署執行器未啟用', detail: 'false · 未啟用，預覽不能執行部署' };
  const shown = value === undefined ? '未提供' : typeof value === 'string' ? value : JSON.stringify(value);
  return { state: 'UNKNOWN', tone: 'amber', badge: '部署執行能力未知', detail: `${shown} · 未知或異常，不視為可執行` };
}

// 預覽回應的 executionEnabled 統計：true 是服務端正常回報（不是異常），false 為不可執行，其他值為未知；實際能否執行仍看 executionReasons。
export function planExecutionStat(value) {
  if (value === true) return { text: 'true', tone: 'teal' };
  if (value === false) return { text: 'false', tone: undefined };
  return { text: value === undefined ? '未提供' : typeof value === 'string' ? value : JSON.stringify(value), tone: 'amber' };
}

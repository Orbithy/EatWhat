// 部署階段與中斷後手動恢復舊版（docs/architecture/control-deployment-recovery-api.md）：純函式。
// - 恢復入口看「目前實際持有租約」（localLeader=true 且 state=HELD），不是接納新任務的寫入閘門；
//   缺少所有權資料時停用並說明，不猜測主控。能力欄位 deploymentRecoveryEnabled 與新部署開關獨立。
// - 只公開白名單欄位；恢復成功不等於新版部署成功。

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SHA_RE = /^[0-9a-f]{64}$/i;
export const isRecoveryUuid = (v) => typeof v === 'string' && UUID_RE.test(v);
const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
const code = (v) => (typeof v === 'string' && /^[A-Z][A-Z0-9_]{0,100}$/.test(v) ? v : null);

export const DEPLOY_STAGE_TEXT = Object.freeze({
  UPLOAD: '上傳中', UPLOADED: '已上傳', BACKUP: '備份', INSTALLING: '安裝', STARTING: '啟動', ROLLING_BACK: '回退中', COMPLETE: '完成',
});
export const deployStageText = (stage) => (typeof stage === 'string' ? DEPLOY_STAGE_TEXT[stage] ?? null : null);

// 目標進度文字：階段（簡短）＋失敗階段；原始代碼另外保留可複製。
export function targetStageLine(target) {
  const parts = [];
  if (code(target?.stage)) parts.push(`階段 ${deployStageText(target.stage) ?? '未定義'}（${target.stage}）`);
  if (code(target?.failureStage)) parts.push(`失敗於 ${deployStageText(target.failureStage) ?? '未定義'}（${target.failureStage}）`);
  return parts.join(' · ');
}

// 刷新鍵：階段或失敗階段改變也要重繪。
export function jobRenderKey(job) {
  if (!job) return '';
  return JSON.stringify([job.id, job.state, job.code, job.reason, (job.targets ?? []).map((t) => [t.site, t.state, t.stage, t.failureStage]),
    (job.recoveryAttempts ?? []).map((a) => [a?.id, a?.state, a?.stage, a?.code])]);
}

export const RECOVERY_STATES = Object.freeze(['PREVIEW_ONLY', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN']);
export const RECOVERY_STATE_COPY = Object.freeze({
  PREVIEW_ONLY: ['neutral', '預覽（尚未執行）'], QUEUED: ['amber', '排隊中'], RUNNING: ['amber', '恢復中'],
  SUCCEEDED: ['teal', '已恢復舊版'], FAILED: ['red', '恢復失敗'], OUTCOME_UNKNOWN: ['red', '結果未知'],
});
export const recoveryActive = (r) => r && (r.state === 'QUEUED' || r.state === 'RUNNING');

// 只保留契約公開欄位；_remoteProof、憑據、capability、資料庫身份等一律不帶入畫面。
export function normalizeRecovery(r) {
  if (!r || typeof r !== 'object' || !isRecoveryUuid(r.id) || !RECOVERY_STATES.includes(r.state)) return null;
  return {
    id: r.id, jobId: isRecoveryUuid(r.jobId) ? r.jobId : null, siteId: str(r.siteId), instanceId: str(r.instanceId), serverId: isRecoveryUuid(r.serverId) ? r.serverId : null,
    epoch: Number.isInteger(r.epoch) ? r.epoch : null,
    restoreSha256: SHA_RE.test(r.restoreSha256 ?? '') ? r.restoreSha256 : null,
    currentSha256: SHA_RE.test(r.currentSha256 ?? '') ? r.currentSha256 : null,
    backupId: str(r.backupId), state: r.state, stage: code(r.stage), code: code(r.code), failureStage: code(r.failureStage),
    createdAt: str(r.createdAt), updatedAt: str(r.updatedAt), expiresAt: str(r.expiresAt),
    executionEnabled: r.executionEnabled === true,
    parentState: code(r.parentState),
    parentReconciliationRequired: typeof r.parentReconciliationRequired === 'boolean' ? r.parentReconciliationRequired : null,
  };
}

export function normalizeAttempts(job) {
  return (Array.isArray(job?.recoveryAttempts) ? job.recoveryAttempts : [])
    .filter((a) => a && isRecoveryUuid(a.id))
    .map((a) => ({ id: a.id, siteId: str(a.siteId), state: code(a.state), stage: code(a.stage), code: code(a.code), updatedAt: str(a.updatedAt) }));
}

export const planExpired = (r, now = Date.now()) => {
  const t = Date.parse(r?.expiresAt ?? '');
  return Number.isFinite(t) && t <= now;
};

// 租約所有權：只有 localLeader=true 且 state=HELD 才算持有；缺資料不猜。
export function leaseReasons(ownership) {
  if (!ownership || ownership.known !== true) return ['缺少目前租約所有權資料，無法確認本程序是主中控。'];
  if (ownership.owned !== true) return ['本程序目前沒有持有控制租約（需 localLeader 且 HELD）；請改用主中控。'];
  return [];
}

export function capabilityReasons(caps) {
  return caps?.deploymentRecoveryEnabled === true ? [] : ['後端未啟用（或未回報）部署恢復能力。'];
}

// 原任務的目標是否可預覽恢復：原任務結果未知，且目標沒有被確認成功或已回退。
export function targetRecoverable(jobState, target) {
  if (jobState !== 'OUTCOME_UNKNOWN') return false;
  return !['SUCCEEDED', 'ROLLED_BACK'].includes(target?.state);
}

export function previewReasons({ caps, ownership, busy, active }) {
  const reasons = [...capabilityReasons(caps), ...leaseReasons(ownership)];
  if (busy) reasons.push('正在處理其他請求。');
  if (active) reasons.push('已有進行中的恢復。');
  return reasons;
}

export function executeReasons({ caps, ownership, plan, busy, now = Date.now() }) {
  const reasons = [...capabilityReasons(caps), ...leaseReasons(ownership)];
  if (busy) reasons.push('正在處理其他請求。');
  if (!plan) { reasons.push('請先建立恢復預覽。'); return reasons; }
  if (plan.state !== 'PREVIEW_ONLY') reasons.push('這份預覽已執行過；同一 UUID 不會再次執行。');
  // 有效期缺失或無法解析：不能當成有效預覽。
  if (!Number.isFinite(Date.parse(plan.expiresAt ?? ''))) reasons.push('預覽有效期未知，不能執行；請重新預覽。');
  else if (planExpired(plan, now)) reasons.push('預覽已過期（5 分鐘），請重新預覽。');
  if (!plan.executionEnabled) reasons.push('預覽未滿足執行條件。');
  if (!plan.restoreSha256) reasons.push('預覽缺少要恢復的完整舊版 SHA-256。');
  return reasons;
}

// 恢復階段（後端 ControlDeploymentRecovery）；未知階段只顯示原碼。
export const RECOVERY_STAGE_TEXT = Object.freeze({ ACCEPTED: '已接受', DISPATCH: '派發', RECOVERY_PREPARED: '已準備', ...DEPLOY_STAGE_TEXT });
export const recoveryStageText = (stage) => (typeof stage === 'string' ? RECOVERY_STAGE_TEXT[stage] ?? null : null);

// 對齊 ControlDeploymentRecovery.java 的安全碼；未知碼原樣保留。
export const RECOVERY_CODE_TEXT = Object.freeze({
  CONTROL_LEASE_NOT_HELD: '這個程序目前沒有持有控制租約。',
  CONTROLLER_RUNTIME_ARTIFACT_UNAVAILABLE: '中控無法證明自身執行中的版本，暫不能恢復。',
  DEPLOYMENT_ALREADY_ACTIVE: '已有另一個發布在進行。',
  DEPLOYMENT_EXECUTOR_CLOSED: '部署執行器正在關閉。',
  DEPLOYMENT_NOT_FOUND: '找不到原部署任務。',
  DEPLOYMENT_RECOVERY_CONFIRMATION_REQUIRED: '需要確認預覽中的完整舊版 SHA-256。',
  DEPLOYMENT_RECOVERY_DISPATCH_NOT_CONFIRMED: '派發未確認，結果未知；只能核對，不能重送。',
  DEPLOYMENT_RECOVERY_HISTORY_CAPACITY: '這個目標的恢復嘗試已達上限。',
  DEPLOYMENT_RECOVERY_NOT_ELIGIBLE: '這個目標目前不符合恢復條件（例如候選版本已健康或已有終態）。',
  DEPLOYMENT_RECOVERY_OUTCOME_REQUIRES_RECONCILIATION: '恢復結果未知，請先核對。',
  DEPLOYMENT_RECOVERY_PLAN_EXPIRED: '恢復預覽已過期，請重新預覽。',
  DEPLOYMENT_RECOVERY_PLAN_NOT_FOUND: '找不到這個恢復預覽。',
  DEPLOYMENT_RECOVERY_PLAN_RECREATE_REQUIRED: '條件已改變，需要重新建立預覽並核對。',
  DEPLOYMENT_RECOVERY_PREVIEW_NOT_VERIFIED: '遠端預覽未通過核對。',
  DEPLOYMENT_RECOVERY_PREVIEW_UNAVAILABLE: '暫時無法建立恢復預覽。',
  DEPLOYMENT_RECOVERY_PROCESS_NOT_OWNED: '這個恢復不屬於目前程序，只能核對。',
  DEPLOYMENT_RECOVERY_RECONCILIATION_UNAVAILABLE: '暫時無法核對遠端記錄。',
  DEPLOYMENT_RECOVERY_REPLY_MISMATCH: '遠端回覆與預覽不符，已停止。',
  DEPLOYMENT_RECOVERY_TARGET_NOT_FOUND: '原任務沒有這個目標站。',
  DEPLOYMENT_STATE_UNAVAILABLE: '部署狀態暫時無法讀取。',
  FROZEN_DEPLOYMENT_BINDING_REQUIRED: '原任務缺少凍結的連線綁定，不能恢復。',
  IDEMPOTENCY_CONFLICT: '同一個請求 UUID 已用於不同內容。',
  INVALID_DEPLOYMENT_TARGETS: '原任務的目標資料無效。',
});
export const recoveryCodeText = (c) => (typeof c === 'string' ? RECOVERY_CODE_TEXT[c] ?? null : null);

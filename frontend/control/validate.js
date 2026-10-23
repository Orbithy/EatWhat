// 送出前的用戶端檢查。伺服器仍是最終判定者；這裡只避免送出明顯不合契約的請求。

import {
  AUDIT_LIMIT_MAX,
  AUDIT_LIMIT_MIN,
  CHANNEL_RE,
  CLUSTER_ID_RE,
  CLUSTER_LIMIT_MAX,
  CLUSTER_LIMIT_MIN,
  CLUSTER_MAX_PREVIEW_SITES,
  FAULT_ACTIONS,
  FAULT_SECONDS_MAX,
  FAULT_SECONDS_MIN,
  RELEASE_ID_RE,
  SITE_IDS,
  SUITE_IDS,
  UUID_RE,
} from './constants.js';

export function isUuid(value) {
  return typeof value === 'string' && UUID_RE.test(value);
}

export function isSiteId(value) {
  return typeof value === 'string' && SITE_IDS.includes(value);
}

export function isChannelName(value) {
  return typeof value === 'string' && CHANNEL_RE.test(value);
}

export function isSuiteId(value) {
  return typeof value === 'string' && SUITE_IDS.includes(value);
}

export function isReleaseId(value) {
  return typeof value === 'string' && RELEASE_ID_RE.test(value);
}

// 只接受純十進位整數（字串可前後空白），拒絕 1.5、1e2、-1、空字串。
export function parseSeconds(raw) {
  if (typeof raw === 'number') return Number.isInteger(raw) ? raw : NaN;
  if (typeof raw !== 'string') return NaN;
  const text = raw.trim();
  if (!/^\d{1,4}$/.test(text)) return NaN;
  return Number(text);
}

export function validateFaultRequest(input, { knownChannels } = {}) {
  const { site, channel, action, seconds } = input ?? {};
  const errors = [];
  if (!isSiteId(site)) errors.push('站點必須是 campus 或 external。');
  if (!isChannelName(channel)) {
    errors.push('請選擇有效的通道。');
  } else if (Array.isArray(knownChannels) && !knownChannels.includes(channel)) {
    errors.push('此通道不在該站點回報的通道清單中。');
  }
  if (!FAULT_ACTIONS.includes(action)) errors.push('動作必須是 BLOCK 或 RESTORE。');
  const parsed = parseSeconds(seconds);
  if (!Number.isInteger(parsed) || parsed < FAULT_SECONDS_MIN || parsed > FAULT_SECONDS_MAX) {
    errors.push(`秒數必須是 ${FAULT_SECONDS_MIN}–${FAULT_SECONDS_MAX} 的整數。`);
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { site, channel, action, seconds: parsed } };
}

export function validatePlanRequest(input, { knownReleaseIds } = {}) {
  const { releaseId, sites } = input ?? {};
  const errors = [];
  if (!isReleaseId(releaseId)) {
    errors.push('請選擇伺服器登記的制品。');
  } else if (Array.isArray(knownReleaseIds) && !knownReleaseIds.includes(releaseId)) {
    errors.push('此制品不在伺服器 /releases 清單中。');
  }
  const list = Array.isArray(sites) ? sites : [];
  const unique = [...new Set(list)];
  if (unique.length === 0) errors.push('至少選擇一個站點。');
  if (unique.some((s) => !isSiteId(s))) errors.push('站點只能是 campus 或 external。');
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { releaseId, sites: SITE_IDS.filter((s) => unique.includes(s)) } };
}

// ---------- 多站叢集 ----------

export function isClusterId(value) {
  return typeof value === 'string' && CLUSTER_ID_RE.test(value);
}

export function isEpoch(value, min = 0) {
  return Number.isSafeInteger(value) && value >= min;
}

export function isClusterLimit(value) {
  return Number.isInteger(value) && value >= CLUSTER_LIMIT_MIN && value <= CLUSTER_LIMIT_MAX;
}

export function validateChangeRequest(input) {
  const errors = [];
  if (!isUuid(input?.requestId)) errors.push('requestId 必須是 UUID。');
  if (!isEpoch(input?.expectedEpoch, 0)) errors.push('expectedEpoch 必須是 ≥ 0 的整數。');
  return errors.length ? { ok: false, errors } : { ok: true, value: { requestId: input.requestId, expectedEpoch: input.expectedEpoch } };
}

export function validateClusterFault(input) {
  const errors = [];
  if (!isClusterId(input?.site)) errors.push('站點 ID 格式無效。');
  if (!isClusterId(input?.channel)) errors.push('請選擇有效的通道。');
  if (!FAULT_ACTIONS.includes(input?.action)) errors.push('動作必須是 BLOCK 或 RESTORE。');
  const seconds = parseSeconds(input?.seconds);
  if (!Number.isInteger(seconds) || seconds < FAULT_SECONDS_MIN || seconds > FAULT_SECONDS_MAX) {
    errors.push(`秒數必須是 ${FAULT_SECONDS_MIN}–${FAULT_SECONDS_MAX} 的整數。`);
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { site: input.site, channel: input.channel, action: input.action, seconds } };
}

export function validateGcBegin(input) {
  const errors = [];
  if (!isUuid(input?.operationId)) errors.push('operationId 必須是 UUID。');
  if (!isUuid(input?.fileId)) errors.push('fileId 必須是 UUID。');
  if (!Number.isSafeInteger(input?.revision) || input.revision < 1) errors.push('revision 必須是 ≥ 1 的整數。');
  if (!isEpoch(input?.expectedEpoch, 1)) errors.push('expectedEpoch 必須是 ≥ 1 的整數。');
  if (errors.length) return { ok: false, errors };
  const { operationId, fileId, revision, expectedEpoch } = input;
  return { ok: true, value: { operationId, fileId, revision, expectedEpoch } };
}

export function validateClusterPreview(input) {
  const errors = [];
  if (!isUuid(input?.requestId)) errors.push('requestId 必須是 UUID。');
  if (!isEpoch(input?.expectedEpoch, 1)) errors.push('expectedEpoch 必須是 ≥ 1 的整數。');
  if (!isClusterId(input?.releaseId)) errors.push('releaseId 格式無效（僅限英數、底線與連字號，1–64 字）。');
  const sites = Array.isArray(input?.sites) ? input.sites : [];
  if (sites.length < 1 || sites.length > CLUSTER_MAX_PREVIEW_SITES) errors.push(`站點數必須介於 1–${CLUSTER_MAX_PREVIEW_SITES}。`);
  if (new Set(sites).size !== sites.length) errors.push('站點不可重複。');
  if (!sites.every(isClusterId)) errors.push('站點 ID 格式無效。');
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { requestId: input.requestId, expectedEpoch: input.expectedEpoch, releaseId: input.releaseId, sites: [...sites] } };
}

export function isValidAuditLimit(value) {
  return Number.isInteger(value) && value >= AUDIT_LIMIT_MIN && value <= AUDIT_LIMIT_MAX;
}

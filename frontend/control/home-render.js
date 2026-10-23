// 多站總覽的畫面。遠端字串（站點 ID、版本 ID）以文字節點呈現並標示 translate="no"，不經簡繁轉換。

import { h, replace } from './dom.js';
import { ROUTES } from './router.js';

function routeLink(route, text) {
  const a = document.createElement('a');
  a.className = 'link';
  a.href = `#/${ROUTES.includes(route) ? route : 'overview'}`;
  a.textContent = `${text} →`;
  return a;
}

const status = (tone, text) => h('span', { class: `status status-${tone}` }, h('span', { class: `dot dot-${tone}`, 'aria-hidden': 'true' }), text);
const raw = (text, cls = '') => h('span', { class: `mono ${cls}`.trim(), translate: 'no' }, text);

const ROUTE_ACTION = { sites: '查看站點', deploy: '查看部署', control: '查看中控', 'advanced/gc': '查看 GC' };

export function renderHome(el, model, { freshnessLine }) {
  const issues = model.issues;
  const issueCard = h(
    'section',
    { class: 'card issues-card', 'aria-labelledby': 'home-issues-title' },
    h('div', { class: 'card-head' }, h('h2', { id: 'home-issues-title' }, `待處理問題（${issues.length}）`), freshnessLine),
    issues.length
      ? h('ul', { class: 'issues' }, issues.map((i) => h('li', { class: 'issue' },
        h('span', { class: `dot dot-${i.tone}`, 'aria-hidden': 'true' }),
        h('div', { class: 'issue-body' }, h('p', { class: 'issue-title' }, i.title), h('p', { class: 'issue-detail' }, i.detail)),
        routeLink(i.route, ROUTE_ACTION[i.route] ?? '前往'))))
      : h('p', { class: 'empty' }, '目前沒有待處理問題。各站就緒、S3 桶存取與存儲讀寫驗證詳見站點頁。'),
  );

  const leader = model.leader;
  const leaderCard = h(
    'section',
    { class: 'card', 'aria-labelledby': 'home-leader-title' },
    h('div', { class: 'card-head' }, h('h2', { id: 'home-leader-title' }, '主中控'), status(leader.tone, leader.writable ? '寫入可用' : '寫入停用')),
    h('p', { class: 'big' }, leader.title),
    h('p', { class: 'hint' }, leader.detail),
    routeLink('control', '中控詳情'),
  );

  const job = model.deployment;
  const deployCard = h(
    'section',
    { class: 'card', 'aria-labelledby': 'home-deploy-title' },
    h('div', { class: 'card-head' }, h('h2', { id: 'home-deploy-title' }, '目前部署'), job ? status(job.tone, job.label) : status('neutral', '未讀取')),
    job
      ? [
          h('p', { class: 'big' }, raw(job.releaseId ?? '（未提供版本）')),
          job.targets.length
            ? h('ul', { class: 'step-list' }, job.targets.map((t) => h('li', { class: 'step-row' },
              h('span', { class: `dot dot-${t.tone}`, 'aria-hidden': 'true' }), raw(t.site), h('span', { class: 'muted' }, t.label))))
            : h('p', { class: 'hint' }, '尚無逐站結果。'),
        ]
      : h('p', { class: 'hint' }, '本次登入尚未讀取部署任務。執行部署或在部署頁依任務 UUID 查詢後會顯示在這裡。'),
    routeLink('deploy', job?.state === 'OUTCOME_UNKNOWN' ? '核對遠端記錄' : '部署詳情'),
  );

  const sites = model.sites;
  const siteCard = h(
    'section',
    { class: 'card', 'aria-labelledby': 'home-sites-title' },
    h('div', { class: 'card-head' }, h('h2', { id: 'home-sites-title' }, '站點狀態'),
      h('span', { class: 'card-sub' }, sites.observable ? `運作中站點 ${sites.reachable}/${sites.observable} 可達` : '')),
    sites.rows.length
      ? h('ul', { class: 'site-rows' }, sites.rows.map((s) => h('li', { class: 'site-row' },
        h('span', { class: 'site-row-name' }, raw(s.siteId, 'strong'), h('span', { class: 'muted small' }, s.campus ? '權威站' : '外站')),
        h('span', { class: 'muted site-row-member' }, s.member),
        status(s.tone, s.observed))))
      : h('p', { class: 'empty' }, sites.known ? '尚無任何成員。' : '成員名單尚未讀取。'),
    model.worker ? h('p', { class: 'home-worker' }, status(model.worker.tone, `worker：${model.worker.label}`), ' ', raw(model.worker.code, 'small')) : null,
    h('p', { class: 'hint' }, '「可達」只代表節點能回應觀測，不等於站點健康。'),
    routeLink('sites', '所有站點'),
  );

  replace(el, issueCard, h('div', { class: 'home-grid' }, leaderCard, deployCard, siteCard));
}

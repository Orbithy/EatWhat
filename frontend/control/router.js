// 登入後的分頁路由：只認 #/route 形式的 hash；其他 hash（例如跳至主要內容的 #main）不影響目前頁面。
// 可見性一律由 [data-page] 決定，各頁的表單 ID 保持不變。

export const ROUTES = Object.freeze(['overview', 'deploy', 'sites', 'servers', 'control', 'advanced/gc', 'advanced/fault', 'advanced/tasks', 'audit', 'tasks', 'account']);
export const DEFAULT_ROUTE = 'overview';

export const ROUTE_TITLES = Object.freeze({
  overview: '總覽', deploy: '部署', sites: '站點', servers: '伺服器', control: '中控',
  'advanced/gc': '進階運維', 'advanced/fault': '進階運維', 'advanced/tasks': '進階運維',
  audit: '審計記錄', tasks: '任務歷史', account: '帳號設定',
});

// 回傳路由字串；不是 #/ 開頭時回傳 null（呼叫端保留目前頁面）。
export function parseRoute(hash) {
  if (typeof hash !== 'string' || !hash.startsWith('#/')) return null;
  const raw = hash.slice(2).replace(/\/+$/, '');
  if (raw === 'advanced') return 'advanced/gc';
  return ROUTES.includes(raw) ? raw : DEFAULT_ROUTE;
}

export function routeSection(route) {
  return route.split('/')[0];
}

export function mountRouter(doc, { onChange }) {
  const win = doc.defaultView;
  let current = parseRoute(win.location.hash) ?? DEFAULT_ROUTE;
  function apply() {
    const section = routeSection(current);
    for (const page of doc.querySelectorAll('[data-page]')) page.hidden = page.dataset.page !== current;
    for (const link of doc.querySelectorAll('[data-route]')) {
      const target = link.dataset.route;
      if (link.dataset.routeGroup === 'true') {
        // 分組連結（進階運維）只標示「已展開」，目前頁面由子連結標示。
        link.classList.toggle('is-open', routeSection(target) === section);
        link.removeAttribute('aria-current');
      } else if (target === current) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
    // 進階運維子選單只在該區展開。
    for (const group of doc.querySelectorAll('[data-route-sub]')) group.hidden = group.dataset.routeSub !== section;
    const title = doc.getElementById('page-title');
    if (title) title.textContent = ROUTE_TITLES[current] ?? '';
    onChange?.(current);
  }
  win.addEventListener('hashchange', () => {
    const next = parseRoute(win.location.hash);
    if (next === null) return;
    current = next;
    apply();
  });
  return {
    current: () => current,
    apply,
  };
}

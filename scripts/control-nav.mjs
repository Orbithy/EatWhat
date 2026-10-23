// 中控改版後的瀏覽器驗收共用導覽：各頁以 #/route 分頁，手機寬度（≤900px）側欄是抽屜。
// 一律透過使用者會點的側欄連結切換，不直接改 location.hash。

const isNarrow = (page) => page.evaluate(() => innerWidth <= 900);

export async function openDrawer(page) {
  if (!(await isNarrow(page))) return;
  if (await page.locator('#shell.nav-open').count()) return;
  await page.locator('#nav-toggle').click();
  await page.locator('#sidebar').waitFor({ state: 'visible' });
}

export async function closeDrawer(page) {
  if (!(await isNarrow(page)) || !(await page.locator('#shell.nav-open').count())) return;
  await page.keyboard.press('Escape');
  await page.locator('#sidebar').waitFor({ state: 'hidden' });
}

export async function gotoRoute(page, route) {
  await openDrawer(page);
  const sub = route.startsWith('advanced/');
  const link = sub ? page.locator(`#app-nav .nav-sub a[data-route="${route}"]`) : page.locator(`#app-nav > a[data-route="${route}"]`);
  if (sub && !(await link.isVisible())) {
    await page.locator('#app-nav a[data-route-group="true"]').click();
    await page.waitForFunction(() => location.hash.startsWith('#/advanced/'));
    if (await isNarrow(page)) {
      await page.locator('#sidebar').waitFor({ state: 'hidden' });
      await openDrawer(page);
    }
  }
  await link.click();
  await page.waitForFunction((r) => location.hash === `#/${r}`
    && [...document.querySelectorAll(`[data-page="${r}"]`)].some((e) => e.offsetParent !== null), route);
  if (await isNarrow(page)) await page.locator('#sidebar').waitFor({ state: 'hidden' });
}

// 手機寬度下語言選單與登出鈕在抽屜裡。
export async function withDrawer(page, action) {
  await openDrawer(page);
  return action();
}

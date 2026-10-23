import { readFile, writeFile, access } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import assert from 'node:assert/strict';

const bridge=JSON.parse(await readFile(process.argv[2],'utf8'));
const {chromium}=createRequire(join(process.env.EATWHAT_PLAYWRIGHT_MODULE,'package.json'))('playwright');
const steps=[];let browser;let diagnostics={};
const record=name=>steps.push(name);
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const report=async(status,extra={})=>writeFile(join(bridge.output,'report.json'),JSON.stringify({status,scope:'LOCAL_CONTROL_BROWSER_REAL_SPRING_AND_TWO_V35_DATABASES',productionDeployment:false,crossRegionAcceptance:'NOT_RUN',steps,...extra},null,2));
try {
  browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1100}});
  const errors=[];page.on('pageerror',e=>errors.push(e.name));page.on('console',msg=>{if(msg.type()==='error'&&!msg.text().includes('Failed to load resource'))errors.push('console-error');});
  const writes=[];page.on('request',r=>{if(r.method()==='POST')writes.push(new URL(r.url()).pathname);});
  await page.goto(bridge.origin);await page.locator('#login-form').waitFor();record('real_static_modules_and_login_page');
  await page.locator('#login-username').fill(bridge.ordinaryUsername);await page.locator('#login-password').fill(bridge.password);const rejected=page.waitForResponse(r=>new URL(r.url()).pathname==='/admin/control/v1/capabilities');await page.locator('#login-submit').click();assert.equal((await rejected).status(),403);await page.waitForFunction(()=>!document.querySelector('#login-error').hidden&&document.querySelector('#login-error').textContent.includes('admin'));assert.equal(await page.locator('#login-password').inputValue(),'');record('ordinary_user_denied_by_real_admin_endpoint');
  await page.locator('#login-username').fill(bridge.username);await page.locator('#login-password').fill(bridge.password);
  const login=page.waitForResponse(r=>new URL(r.url()).pathname==='/auth/login');await page.locator('#login-submit').click();const lr=await login;const loginText=await lr.text();diagnostics={loginStatus:lr.status(),loginContentType:lr.headers()['content-type']??'',loginResponseLength:loginText.length};assert.equal(lr.status(),200);assert.equal(JSON.parse(loginText).code,0);await page.locator('#console-view').waitFor({state:'visible'});record('real_spring_login_and_admin_header_authentication');
  await page.waitForFunction(()=>document.querySelector('#site-grid').textContent.includes('DATABASE_OBSERVATION_UNAVAILABLE'));record('actual_external_read_failure_is_unknown');
  assert.equal(await page.locator('#deploy-execute').isDisabled(),true);assert.equal(await page.locator('#login-password').inputValue(),'');record('deploy_locked_and_password_cleared');
  await page.locator('#fault-site').selectOption('external');await page.locator('input[name="fault-action"][value="RESTORE"]').check();
  await page.locator('#fault-channel').selectOption('storage');await page.waitForFunction(()=>!document.querySelector('#fault-submit').disabled);record('first_login_offline_recovery_catalog_available');
  const restored=page.waitForResponse(r=>new URL(r.url()).pathname==='/admin/control/v1/sites/external/faults');await page.locator('#fault-submit').click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-confirm').click();
  const rr=await restored;assert.equal(rr.status(),200);assert.equal((await rr.json()).channel.state,'FORWARDING');record('real_unknown_database_node_restore_succeeds');
  await writeFile(bridge.stage,JSON.stringify({stage:'RESTORED_OFFLINE'}));
  const deadline=Date.now()+30000;while(Date.now()<deadline){try{await access(bridge.resume);break;}catch{await pause(100);}}await access(bridge.resume);
  await page.locator('#refresh-button').click();await page.waitForFunction(()=>document.querySelector('#pair-bar').textContent.includes('已核對為不同資料庫'));record('two_real_database_identities_become_verified_distinct');
  await page.locator('#fault-site').selectOption('campus');await page.locator('input[name="fault-action"][value="BLOCK"]').check();await page.locator('#fault-channel').selectOption('events');
  await page.locator('#fault-seconds').fill('3');assert.equal(await page.locator('#fault-submit').isDisabled(),true);record('block_above_real_node_limit_is_disabled');
  await page.locator('#fault-seconds').fill('2');await page.waitForFunction(()=>!document.querySelector('#fault-submit').disabled);
  const before=writes.length;await page.locator('#fault-submit').click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-cancel').click();assert.equal(writes.length,before);record('cancel_confirmation_causes_no_write');
  const blocked=page.waitForResponse(r=>new URL(r.url()).pathname==='/admin/control/v1/sites/campus/faults');await page.locator('#fault-submit').click();await page.locator('#dialog-confirm').click();const br=await blocked;assert.equal(br.status(),200);assert.equal((await br.json()).channel.state,'BLOCKED');record('real_configured_tcp_channel_block_accepted');
  await page.waitForFunction(()=>document.querySelector('#audit').textContent.includes('AUTO_RESTORE'),{},{timeout:20000});record('automatic_restore_recorded_by_real_node_audit');
  await page.locator('#release-select').selectOption('synthetic');const planned=page.waitForResponse(r=>new URL(r.url()).pathname==='/admin/control/v1/deployment-plans');await page.locator('#plan-create').click();const pr=await planned;assert.equal(pr.status(),201);const plan=await pr.json();assert.equal(plan.state,'PREVIEW_ONLY');assert.equal(plan.executionEnabled,false);assert.equal(await page.locator('#deploy-execute').isDisabled(),true);record('real_artifact_sha_deployment_preview_with_execution_locked');
  assert.equal(writes.some(path=>path.endsWith('/execute')),false);assert.equal(writes.some(path=>path==='/admin/control/v1/test-runs'),false);record('no_deploy_or_verification_job_executed');
  assert.deepEqual(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length,cookie:document.cookie})),{local:0,session:0,cookie:''});record('no_browser_persisted_admin_credentials');
  await page.screenshot({path:join(bridge.output,'wide.png'),fullPage:true});await page.screenshot({path:join(bridge.output,'wide-viewport.png')});await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:join(bridge.output,'mobile.png'),fullPage:true});
  const layout=await page.evaluate(()=>({viewport:innerWidth,scrollWidth:document.documentElement.scrollWidth,overflow:[...document.querySelectorAll('body *')].map(e=>({tag:e.tagName,id:e.id,className:typeof e.className==='string'?e.className:'',right:Math.round(e.getBoundingClientRect().right),width:Math.round(e.getBoundingClientRect().width)})).filter(e=>e.right>innerWidth+1).slice(0,20)}));diagnostics={...diagnostics,layout};assert.equal(layout.scrollWidth<=layout.viewport,true);record('desktop_and_mobile_layout_no_horizontal_overflow');
  await page.evaluate(()=>{scrollTo(0,0);document.querySelectorAll('details').forEach(e=>e.open=true);});await page.screenshot({path:join(bridge.output,'mobile-expanded.png'),fullPage:true});await page.screenshot({path:join(bridge.output,'mobile-viewport.png')});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);record('mobile_all_technical_details_expanded_without_page_overflow');
  await page.locator('#logout-button').click();await page.locator('#login-view').waitFor({state:'visible'});assert.equal(await page.locator('#login-password').inputValue(),'');record('logout_clears_session_view');
  assert.deepEqual(errors,[]);record('no_javascript_or_csp_errors');await report('PASS',{browserVersion:browser.version()});
}catch(error){await report('FAIL',{reason:error.name,diagnostics});process.exitCode=1;}
finally{if(browser)await browser.close();}

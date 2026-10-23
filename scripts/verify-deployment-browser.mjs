import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, join } from 'node:path';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { gotoRoute, withDrawer } from './control-nav.mjs';
const root=resolve('frontend/control');
const { chromium }=createRequire(join(process.env.EATWHAT_PLAYWRIGHT_MODULE,'package.json'))('playwright');
const id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', instance='11111111-1111-4111-8111-111111111111';
const sha='a'.repeat(64), errors=[], steps=[];
let mode='cluster', epoch=1, executions=0, reconciliations=0, unknown=false;
const member={siteId:'campus',instanceId:instance,mode:'CAMPUS',allocationSlot:0};
const caps={topology:'SINGLE_CLUSTER',maximumActiveSites:16,allocationSlots:1024,coordinatorTransferEnabled:false,businessSessionShared:true,deploymentExecutionEnabled:true,remoteFailureMatrixAccepted:false};
const plan={id,releaseId:'candidate',artifactSha256:sha,state:'PREVIEW_ONLY',executionEnabled:true,blockers:[],steps:[],members:[member],sites:['campus','external'],epoch:1};
const status=()=>({clusterId:id,epoch,members:[{site_id:'campus',instance_id:instance,mode:'CAMPUS',allocation_slot:0,state:'ACTIVE'}],pendingChanges:[],worker:{running:true,lastCode:'OK'},leadership:{state:'SINGLE_PROCESS',localLeader:true}});
const job=()=>({id,releaseId:'candidate',state:unknown?'OUTCOME_UNKNOWN':'SUCCEEDED',targets:[{site:'campus',state:unknown?'DEPLOYING':'SUCCEEDED'}]});
const server=createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://localhost'); let data;
  if(url.pathname==='/auth/login') data={code:0,data:{tokenName:'satoken',tokenValue:'synthetic-browser-token'}};
  else if(url.pathname.startsWith('/admin/control/v1')){
   const p=url.pathname;
   if(p.endsWith('/capabilities'))data=caps;
   else if(p.endsWith('/leadership'))data={state:'SINGLE_PROCESS',localLeader:true};
   else if(p==='/admin/control/v1/cluster')data=status();
   else if(p.endsWith('/observations'))data=[];
   else if(p.endsWith('/releases'))data=[{id:'candidate',sha256:sha,acceptanceEvidenceId:'reviewed'}];
   else if(p.endsWith('/sites'))data={pairStatus:'VERIFIED_DISTINCT',sites:{},observedAt:new Date().toISOString()};
   else if(p.endsWith('/execute')){executions++;data={...job(),state:'QUEUED'};res.statusCode=202;}
   else if(p.endsWith('/reconcile')){reconciliations++;unknown=false;data=job();}
   else if(p.includes('/deployments/'))data=job();
   else if(p.includes('/deployment-plans/'))data=plan;
   else if(p.endsWith('/deployment-plans')){data=plan;res.statusCode=201;}
   else data=[];
  }else{
   const path=resolve(root,'.'+(url.pathname==='/'?'/index.html':url.pathname));
   assert.ok(path.startsWith(root+'/'));
   let content=await readFile(path);
   if(path.endsWith('constants.js')&&mode==='legacy')content=Buffer.from(content.toString().replace("CONTROL_MODE = 'cluster'","CONTROL_MODE = 'legacy'"));
   res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[extname(path)]??'application/octet-stream');res.end(content);return;
  }
  res.setHeader('Content-Type','application/json');res.end(JSON.stringify(data));
 }catch{res.statusCode=500;res.end('{}');}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true});
const output=resolve('output/playwright/deployment-feature');await mkdir(output,{recursive:true});
try{
 for(mode of ['cluster','legacy']){
  epoch=1;unknown=false;const page=await browser.newPage({viewport:{width:1440,height:1100}});
  page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(() => localStorage.setItem('eatwhat.control.locale', 'zh-Hant'));
 await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.locator('#login-username').fill('admin');await page.locator('#login-password').fill('test-password');await page.locator('#login-submit').click();
  const prefix=mode==='cluster'?'cl-':'';
  await page.locator(`#${mode==='cluster'?'cluster-view':'console-view'}`).waitFor({state:'visible'});
  await gotoRoute(page,'deploy');
  await page.locator(`#${prefix}release-select`).selectOption('candidate');
  if(mode==='cluster')await page.locator('#cl-plan-sites input').first().check();
  await page.locator(`#${prefix}plan-create`).click();
  if(mode==='cluster'){await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-confirm').click();}
  const execute=page.locator(`#${prefix}deploy-execute`);await execute.waitFor();
  await page.waitForFunction(sel=>!document.querySelector(sel).disabled,`#${prefix}deploy-execute`);
  let before=executions;
  await execute.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-cancel').click();
  assert.equal(executions,before);steps.push(`${mode}:cancel_confirmation_has_no_effect`);
  await execute.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-confirm').click();
  await page.waitForFunction(()=>document.body.textContent.includes('任務 aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa · SUCCEEDED'));
  assert.equal(executions,before+1);steps.push(`${mode}:execute_once_and_poll_persisted_result`);
  unknown=true;
  const lookup=page.locator(`#${prefix}deploy-execute`).locator('..').locator('form');
  await lookup.locator('input').fill(id);await lookup.locator('button').click();
  await page.waitForFunction(()=>document.body.textContent.includes('任務 aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa · OUTCOME_UNKNOWN'));
  const reconcile=page.getByRole('button',{name:'核對遠端部署紀錄',exact:true}).filter({visible:true});
  await reconcile.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-confirm').click();
  await page.waitForFunction(()=>document.body.textContent.includes('任務 aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa · SUCCEEDED'));
  steps.push(`${mode}:unknown_result_reconciles_without_redeployment`);
  assert.equal(executions,before+1);
  if(mode==='cluster'){
   await execute.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});epoch=2;
   await page.evaluate(()=>document.querySelector('#refresh-button').click());
   await page.waitForFunction(()=>document.querySelector('#cl-deploy-execute').disabled);
   await page.locator('#dialog-confirm').click();
   await page.waitForFunction(()=>!document.querySelector('#confirm-dialog').open);
   assert.equal(executions,before+1);steps.push('cluster:changed_epoch_during_confirmation_blocks_write');
  }
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.screenshot({path:join(output,mode+'-mobile.png'),fullPage:true});
  await withDrawer(page,()=>page.locator('#logout-button').click());await page.locator('#login-form').waitFor({state:'visible'});
  assert.equal(await page.locator('input[maxlength="36"]').evaluateAll(inputs=>inputs.every(i=>i.value==='')),true);
  steps.push(`${mode}:mobile_layout_and_logout_clear_task_state`);
  await page.close();
 }
 assert.deepEqual(errors,[]);assert.equal(reconciliations,2);
 await writeFile(join(output,'report.json'),JSON.stringify({status:'PASS',scope:'LOCAL_BROWSER_WITH_SIMULATED_DEPLOYMENT_API',productionDeployment:false,steps},null,2));
 process.stdout.write(JSON.stringify({status:'PASS',steps:steps.length,output})+'\n');
}finally{await browser.close();await new Promise(r=>server.close(r));}

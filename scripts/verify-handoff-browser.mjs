import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, join } from 'node:path';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { gotoRoute, withDrawer } from './control-nav.mjs';
const root=resolve('frontend/control');
const { chromium }=createRequire(join(process.env.EATWHAT_PLAYWRIGHT_MODULE,'package.json'))('playwright');
const a='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',b='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
let generation=1,leader=a,handoff={state:'NONE'},requests=0,cancels=0,lookups=0,loseResponse=false,shared=true;
const steps=[],errors=[];
const record=(value)=>{steps.push(value);console.log(value);};
const caps={topology:'SINGLE_CLUSTER',maximumActiveSites:16,allocationSlots:1024,coordinatorTransferEnabled:false,leadershipHandoffEnabled:true,businessSessionShared:true,deploymentExecutionEnabled:false,remoteFailureMatrixAccepted:false};
const leadership=()=>({state:'HELD',localLeader:leader===b,generation,localControllerId:b,leaderControllerId:leader,handoffEnabled:true,handoff,controllers:[{id:a,name:'campus-control',sharedState:true},{id:b,name:'outside-control',sharedState:shared}]});
const server=createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://localhost');let data;
  if(url.pathname==='/auth/login')data={code:0,data:{tokenName:'satoken',tokenValue:'synthetic-handoff-browser-token'}};
  else if(url.pathname.startsWith('/admin/control/v1')){
   if(url.pathname.endsWith('/capabilities'))data=caps;
   else if(url.pathname.endsWith('/leadership'))data=leadership();
   else if(url.pathname==='/admin/control/v1/cluster')data={clusterId:a,epoch:0,members:[],pendingChanges:[],worker:{running:true,lastCode:'AWAITING_FIRST_JOIN'},leadership:leadership()};
   else if(req.method==='POST'&&url.pathname.endsWith('/leadership/handoffs')){
    let text='';for await(const part of req)text+=part;const body=JSON.parse(text);requests++;
    assert.deepEqual(Object.keys(body).sort(),['expectedGeneration','requestId','targetControllerId']);assert.equal(body.expectedGeneration,generation);assert.equal(body.targetControllerId,b);
    handoff={id:body.requestId,state:'WAITING',sourceControllerId:a,targetControllerId:b,expectedGeneration:generation,reason:'DEPLOYMENT_RUNNING'};
    if(loseResponse){loseResponse=false;return;}data=handoff;res.statusCode=202;
   }else if(req.method==='POST'&&url.pathname.endsWith('/cancel')){cancels++;handoff={...handoff,state:'CANCELLED',reason:'ADMIN_CANCELLED'};data=handoff;}
   else if(url.pathname.includes('/leadership/handoffs/')){lookups++;data=handoff;}
   else data=[];
  }else{
   const path=resolve(root,'.'+(url.pathname==='/'?'/index.html':url.pathname));assert.ok(path.startsWith(root+'/'));
   res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[extname(path)]??'application/octet-stream');res.end(await readFile(path));return;
  }
  res.setHeader('Content-Type','application/json');res.end(JSON.stringify(data));
 }catch{res.statusCode=500;res.end('{}');}
});
await new Promise(done=>server.listen(0,'127.0.0.1',done));
const browser=await chromium.launch({headless:true}),page=await browser.newPage({viewport:{width:1440,height:1100}});
const output=resolve('output/playwright/control-handoff');await mkdir(output,{recursive:true});page.on('pageerror',error=>errors.push(error.message));
page.setDefaultTimeout(15000);
try{
 await page.addInitScript(() => localStorage.setItem('eatwhat.control.locale', 'zh-Hant'));
 await page.goto(`http://127.0.0.1:${server.address().port}`);
 await page.locator('#login-username').fill('admin');await page.locator('#login-password').fill('synthetic');await page.locator('#login-submit').click();
 await page.locator('#cluster-view').waitFor({state:'visible'});await gotoRoute(page,'control');
 const submit=page.locator('#cl-handoff-submit'),cancel=page.locator('#cl-handoff-cancel');
 const enabled=async(locator)=>{await locator.waitFor({state:'visible'});await page.waitForFunction(id=>!document.getElementById(id).disabled,await locator.getAttribute('id'));};
 await enabled(submit);assert.equal(await page.locator('#cl-handoff-target').inputValue(),b);record('standby_admin_can_choose_registered_live_target');
 await submit.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-cancel').click();assert.equal(requests,0);record('cancel_confirmation_sends_no_request');
 await enabled(submit);await submit.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-confirm').click();
 await enabled(cancel);assert.equal(requests,1);assert.match(await page.locator('#cl-handoff-status').textContent(),/等待目前部署完成/);record('request_waits_and_exposes_deployment_blocker');
 await cancel.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-confirm').click();await enabled(submit);assert.equal(cancels,1);record('waiting_handoff_can_be_cancelled');
 await submit.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});generation++;
 await page.locator('#dialog-confirm').click();await page.waitForFunction(()=>document.getElementById('cl-handoff-result').textContent.includes('狀態已改變'));assert.equal(requests,1);record('generation_change_during_confirmation_sends_nothing');
 await enabled(submit);loseResponse=true;await submit.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-confirm').click();
 await page.waitForFunction(()=>document.getElementById('cl-handoff-result').textContent.includes('結果未知'));assert.equal(requests,2);
 await enabled(submit);await submit.click();await page.locator('#confirm-dialog').waitFor({state:'visible'});await page.locator('#dialog-confirm').click();
 await page.waitForFunction(()=>document.getElementById('cl-handoff-result').textContent.includes('WAITING'));assert.equal(requests,2);assert.equal(lookups,1);record('lost_response_is_looked_up_without_duplicate_handoff');
 handoff={...handoff,reason:'DEPLOYMENT_RECONCILIATION_REQUIRED'};await page.waitForFunction(()=>document.getElementById('cl-handoff-status').textContent.includes('核對遠端'));record('unknown_deployment_requires_reconciliation');
 leader=b;generation++;handoff={...handoff,state:'COMPLETED',reason:'TARGET_ACKNOWLEDGED'};
 await page.waitForFunction(()=>document.getElementById('cl-handoff-status').textContent.includes('COMPLETED'));record('completion_comes_from_polled_server_status');
 await page.setViewportSize({width:390,height:844});await page.locator('#cl-handoff').scrollIntoViewIfNeeded();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.screenshot({path:join(output,'handoff-390.png'),fullPage:true});record('narrow_view_has_no_horizontal_overflow');
 await withDrawer(page,()=>page.getByRole('button',{name:'登出',exact:true}).click());assert.equal(await page.locator('#cl-handoff').isVisible(),false);record('logout_clears_handoff_ui_and_pending_intent');
 assert.deepEqual(errors,[]);await writeFile(join(output,'verification.json'),JSON.stringify({passed:true,steps,pageErrors:errors},null,2));console.log(JSON.stringify({passed:true,checks:steps.length,steps}));
}catch(error){console.error(error);await page.screenshot({path:join(output,'failure.png'),fullPage:true});throw error;}
finally{await browser.close();server.closeAllConnections();await new Promise(done=>server.close(done));}

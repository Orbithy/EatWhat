// Browser acceptance driver; only uses the test-owned loopback application and storage URLs supplied by it.
import fs from 'node:fs/promises';
import { createRequire } from 'node:module';
const require=createRequire(import.meta.url);
const cfg=JSON.parse(await fs.readFile(process.argv[2],'utf8'));
const { chromium }=require(process.env.EATWHAT_PLAYWRIGHT_MODULE);
const steps=[],networkFailures=[],browserErrors=[];
const redact=value=>String(value).replaceAll(cfg.token,'<redacted-token>').replace(/https?:\/\/[^\s)]+/g,'<redacted-url>').slice(0,400);
const write=async(path,value)=>{await fs.writeFile(path+'.tmp',JSON.stringify(value,null,2)+'\n',{mode:0o600});await fs.rename(path+'.tmp',path);};
let browser,phase='launch';
try {
  browser=await chromium.launch({headless:true});
  const context=await browser.newContext();
  const page=await context.newPage();
  page.on('requestfailed',r=>networkFailures.push({method:r.method(),path:new URL(r.url()).pathname.split('/').slice(0,3).join('/'),failure:r.failure()?.errorText}));
  page.on('console',m=>{if(m.type()==='error')browserErrors.push(redact(m.text()));});
  await page.goto(cfg.origin+'/acceptance');
  phase='browser_upload_reference_release_restore';
  const first=await page.evaluate(async c=>{
    const steps=[];
    const check=(condition,step)=>{if(!condition)throw new Error('CHECK_'+step);steps.push(step);};
    const api=async(method,path,body)=>{
      const response=await fetch(c.api+path,{method,mode:'cors',credentials:'include',headers:{'Content-Type':'application/json','eatwhat-test-token':'Bearer '+c.token},body:body===undefined?undefined:JSON.stringify(body)});
      check(response.status===200,'http_'+method+'_'+path.split('/')[1]);const result=await response.json();check(result.code===0,'business_'+method+'_'+path.split('/')[1]);return result.data;
    };
    const bytes=Uint8Array.from(atob(c.payload),x=>x.charCodeAt(0));
    const hash=async(data)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',data)),x=>x.toString(16).padStart(2,'0')).join('');
    const sha=await hash(bytes);
    const signed=await api('POST','/upload/presign',{biz:'foods',fileName:'browser.png',contentType:'image/png',fileSize:bytes.length});
    const put=await fetch(signed.putUrl,{method:'PUT',mode:'cors',credentials:'omit',headers:{'Content-Type':'image/png'},body:bytes});
    check(put.status===200,'s3_browser_put');check(!!put.headers.get('ETag'),'s3_etag_exposed');
    const complete=await api('POST','/upload/complete',{key:signed.key});check(complete.sha256===sha,'complete_sha256');
    const create=async(name)=>{await api('POST','/foods/upload',{restaurantId:c.restaurant,name,pictureUrl:[signed.key]});const list=await api('GET','/foods/my?page=1&pageSize=20');const food=list.items.find(x=>x.name===name);check(!!food,'business_food_found');return food.id;};
    let food=await create('Browser first');const detail=await api('GET','/foods/detail/'+food);check(detail.pictureUrl.length===1,'local_signed_url_visible');
    const get=await fetch(detail.pictureUrl[0],{mode:'cors',credentials:'omit'});check(get.status===200,'s3_browser_get');
    const blob=await get.blob();check(await hash(await blob.arrayBuffer())===sha,'browser_origin_bytes_sha256');
    const bitmap=await createImageBitmap(blob);check(bitmap.width===1&&bitmap.height===1,'browser_decodes_real_png');bitmap.close();
    await api('DELETE','/foods/delete/'+food);food=await create('Browser restored');
    const restored=await api('GET','/foods/detail/'+food);check(restored.pictureUrl.length===1,'restored_reference_readable');
    return {status:'PASS',key:signed.key,sha256:sha,foodId:food,steps,putUrl:signed.putUrl};
  },cfg);
  steps.push(...first.steps);
  await write(cfg.stage,{status:'PASS',key:first.key,sha256:first.sha256,foodId:first.foodId});
  phase='wait_actual_replication_and_receipts';
  const deadline=Date.now()+120000;
  while(Date.now()<deadline){try{await fs.access(cfg.resume);break;}catch{await new Promise(resolve=>setTimeout(resolve,100));}}
  await fs.access(cfg.resume);
  phase='browser_cdn';
  const publicUrl=await page.evaluate(async ({c,first})=>{
    const response=await fetch(c.api+'/foods/detail/'+first.foodId,{credentials:'include',headers:{'eatwhat-test-token':'Bearer '+c.token}});
    const result=await response.json();if(result.code!==0)throw new Error('CDN business response');const url=result.data.pictureUrl[0];
    if(new URL(url).search)throw new Error('Expected public CDN path');
    const image=await fetch(url,{mode:'cors',credentials:'omit'});if(image.status!==200)throw new Error('CDN status');
    const actual=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await image.arrayBuffer())),x=>x.toString(16).padStart(2,'0')).join('');
    if(actual!==first.sha256)throw new Error('CDN bytes');return url;
  },{c:cfg,first});
  steps.push('browser_cdn_read_after_real_replication_and_receipts');
  phase='untrusted_origin';
  const deniedContext=await browser.newContext();const denied=await deniedContext.newPage();
  await denied.goto(cfg.deniedOrigin+'/acceptance');
  const blocked=await denied.evaluate(async ({c,putUrl,publicUrl})=>{
    const rejected=async(url,options)=>{try{await fetch(url,{mode:'cors',...options});return false;}catch(error){return error instanceof TypeError;}};
    return {
      api:await rejected(c.api+'/upload/presign',{method:'POST',credentials:'include',headers:{'Content-Type':'application/json','eatwhat-test-token':'Bearer '+c.token},body:JSON.stringify({biz:'foods',fileName:'blocked.png',contentType:'image/png',fileSize:98304})}),
      s3:await rejected(putUrl,{method:'PUT',credentials:'omit',headers:{'Content-Type':'image/png'},body:Uint8Array.from(atob(c.payload),x=>x.charCodeAt(0))}),
      cdn:await rejected(publicUrl,{credentials:'omit'})
    };
  },{c:cfg,putUrl:first.putUrl,publicUrl});
  for(const [boundary,denied] of Object.entries(blocked)){if(!denied)throw new Error('Untrusted origin was accepted');steps.push('untrusted_origin_blocked_'+boundary);}
  await deniedContext.close();
  await write(cfg.output,{status:'PASS',scope:'LOCAL_BROWSER_BUSINESS_RUSTFS_AND_TEST_CDN',browser:browser.version(),steps,sha256:first.sha256,key:first.key,cross_region_partition:'DEFERRED',production_deployment:false});
} catch(error) {
  await write(cfg.output,{status:'FAIL',phase,error_class:error.name,error_code:String(error.message).match(/CHECK_([a-zA-Z0-9_]+)/)?.[1]??'REDACTED',diagnostic:String(error.message).replaceAll(cfg.token,'<redacted-token>').replace(/https?:\/\/[^\s)]+/g,'<redacted-url>').split('\n')[0].slice(0,240),steps,networkFailures,browserErrors,production_deployment:false});process.exitCode=1;
} finally {if(browser)await browser.close();}

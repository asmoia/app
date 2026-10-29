// Opt-in real browser qualification. Profile/config/log output is private.
'use strict';
const fs=require('node:fs'),path=require('node:path'),net=require('node:net');
const {parseArgs}=require('node:util'),{spawn}=require('node:child_process');
const {chromium}=require(process.env.SOAR_PLAYWRIGHT||'playwright');
const {values:args}=parseArgs({options:{engine:{type:'string'},profile:{type:'string'},output:{type:'string'},url:{type:'string',multiple:true},'expected-ip':{type:'string'}}});
if(!args.engine||!args.profile||!args.output)throw Error('--engine, --profile and --output are required; keep the output private');
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const cleanURL=value=>{try{const u=new URL(value);return u.origin+u.pathname;}catch{return '[invalid URL]';}};
let browser,engine,log;
(async()=>{try{
 const root=path.resolve(args.output);fs.mkdirSync(root,{recursive:true,mode:0o700});
 const out=fs.mkdtempSync(path.join(root,'browser-'));fs.chmodSync(out,0o700);
 const record=JSON.parse(fs.readFileSync(args.profile,'utf8'));const payload=record.link?JSON.parse(Buffer.from(record.link.replace(/^soar:\/\//,''),'base64url')):record;const profile=payload.profile||payload;
 if(!profile.server?.domain||!profile.server?.public_key||!profile.soarSettings)throw Error('A complete exported Soar profile is required');
 const port=await new Promise((resolve,reject)=>{const s=net.createServer();s.on('error',reject);s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p));});});
 const config=path.join(out,'engine.json'),resolvers=path.join(out,'resolvers.txt'),logfile=path.join(out,'engine.log');
 fs.writeFileSync(config,JSON.stringify({zone:profile.server.domain,server_public_key:profile.server.public_key,listen:'127.0.0.1:'+port,soar:profile.soarSettings}),{mode:0o600});fs.writeFileSync(resolvers,(profile.resolvers||[]).join('\n')+'\n',{mode:0o600});
 log=fs.openSync(logfile,'w',0o600);engine=spawn(path.resolve(args.engine),['-config',config,'-resolvers',resolvers],{stdio:['ignore',log,log]});
 const started=Date.now();while(!fs.readFileSync(logfile,'utf8').includes('SOAR_READY authenticated=1 https=1')){if(engine.exitCode!==null)throw Error('Engine failed before readiness; inspect the private log');if(Date.now()-started>120000)throw Error('Engine startup timeout');await pause(200);}
 const engineStartupSeconds=(Date.now()-started)/1000;
 browser=await chromium.launch({headless:true,...(process.env.SOAR_CHROME?{executablePath:process.env.SOAR_CHROME}:{}),proxy:{server:'socks5://127.0.0.1:'+port},args:['--disable-quic','--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1']});
 const results=[];
 if(args['expected-ip']){const context=await browser.newContext();const page=await context.newPage();await page.goto('https://api.ipify.org/',{waitUntil:'domcontentloaded',timeout:90000});const ip=(await page.locator('body').innerText()).trim();if(ip!==args['expected-ip'])throw Error('Unexpected tunnel exit IP');await context.close();}
 for(const url of args.url||['https://example.com/','https://amazon.co.uk/']){
  if(new URL(url).protocol!=='https:')throw Error('Qualification URLs must use HTTPS');
  // A fresh context prevents a repeat from passing solely through a browser cache.
  const context=await browser.newContext();const page=await context.newPage(),responses=[],failures=[];page.on('response',r=>responses.push({url:cleanURL(r.url()),status:r.status()}));page.on('requestfailed',r=>{if(r.failure()?.errorText!=='net::ERR_ABORTED')failures.push({url:cleanURL(r.url()),error:r.failure()?.errorText});});
  const start=Date.now();let status=0,error='';try{const response=await page.goto(url,{waitUntil:'domcontentloaded',timeout:90000});status=response?.status()||0;}catch(e){error=e.message.split('\n')[0];}
  const domSeconds=(Date.now()-start)/1000;await pause(8000);
  const content=await page.evaluate(()=>({title:document.title,htmlBytes:new TextEncoder().encode(document.documentElement.outerHTML).length,resources:performance.getEntriesByType('resource').length,challenge:/continue shopping|captcha|verify you are human/i.test(document.body?.innerText||'')})).catch(()=>({}));
  const result={url:cleanURL(url),status,domSeconds,responses:responses.length,failedResources:failures.length,failures,httpErrors:responses.filter(r=>r.status>=400),error,...content};results.push(result);console.log(JSON.stringify(result));await page.screenshot({path:path.join(out,'page-'+results.length+'.png'),timeout:10000}).catch(()=>{});await context.close();
 }
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({engineStartupSeconds,results},null,2),{mode:0o600});
 if(results.some(r=>r.error||!r.status))process.exitCode=1;
 console.log('Private browser evidence: '+out);
}finally{if(browser)await browser.close();if(engine&&engine.exitCode===null){engine.kill();await Promise.race([new Promise(r=>engine.once('exit',r)),pause(5000)]);}if(log!==undefined)fs.closeSync(log);}})().catch(e=>{console.error(e.message);process.exitCode=1;});

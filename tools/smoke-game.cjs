/* Read-only real-game startup test in desktop Chromium, with Android UA and native-pad samples. */
const fs=require('node:fs'), path=require('node:path'), http=require('node:http'), cp=require('node:child_process');
const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
let game=path.resolve(process.argv[2]||'F:/DepotDownloaderMod/Look Outside');
if(!fs.existsSync(path.join(game,'index.html')))game=path.join(game,'www');
const edge=process.argv[3]||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const out=path.join(root,'test-results');fs.mkdirSync(out,{recursive:true});
const profile=fs.mkdtempSync(path.join(out,'browser-'));
const bridge=['ui-strings.js','bridge.js'].map(name=>fs.readFileSync(path.join(root,'app/src/main/assets',name),'utf8')).join('\n');
const index=fs.readFileSync(path.join(game,'index.html'),'utf8').replace(/<head\b[^>]*>/i,m=>m+'<script src="/__padport__/bridge.js"></script>');
let requests=0;const missing=[];
const server=http.createServer((req,res)=>{
    const rel=decodeURIComponent(new URL(req.url,'http://localhost').pathname).replace(/^\//,'');
    if(rel==='__padport__/bridge.js'){
        res.setHeader('Content-Type','application/javascript');
        return res.end(`window.__native={revision:1,paused:false,pads:[]};window.__keyEvents=0;addEventListener('keydown',()=>__keyEvents++);
        window.__PADPORT_CONFIG__={title:'Look Outside',engine:'MZ'};
        window.PadPortHost={snapshot:()=>JSON.stringify(__native),log:m=>console.debug(m),exportReady:s=>window.__exported=s,saveError:console.error,importReady:()=>{}};`+bridge);
    }
    if(!rel||rel==='index.html'){res.setHeader('Content-Type','text/html');return res.end(index);}
    let file=path.resolve(game,rel);
    if(!file.startsWith(game+path.sep)){res.writeHead(403);return res.end();}
    if(!fs.existsSync(file))file=file.replace(/\.m4a(_?)$/,'.ogg$1').replace(/\.rpgmvm$/,'.rpgmvo');
    if(!fs.existsSync(file)||!fs.statSync(file).isFile()){missing.push(rel);res.writeHead(404);return res.end(rel);}
    requests++;
    const mime={'.js':'application/javascript','.json':'application/json','.wasm':'application/wasm','.png':'image/png','.ogg':'audio/ogg','.css':'text/css','.ttf':'font/ttf'};
    res.setHeader('Content-Type',mime[path.extname(file)]||'application/octet-stream');
    const size=fs.statSync(file).size,match=(req.headers.range||'').match(/^bytes=(\d+)-(\d*)$/);
    if(match){const start=Number(match[1]),end=match[2]?Math.min(Number(match[2]),size-1):size-1;
        res.writeHead(206,{'Content-Range':`bytes ${start}-${end}/${size}`,'Content-Length':end-start+1});fs.createReadStream(file,{start,end}).pipe(res);
    }else fs.createReadStream(file).pipe(res);
});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
class CDP{
    constructor(url){this.ws=new WebSocket(url);this.id=0;this.pending=new Map();this.errors=[];this.console=[];
        this.ready=new Promise((resolve,reject)=>{this.ws.onopen=resolve;this.ws.onerror=reject;});
        this.ws.onmessage=event=>{const msg=JSON.parse(event.data);if(msg.id){const p=this.pending.get(msg.id);if(p){this.pending.delete(msg.id);msg.error?p.reject(msg.error):p.resolve(msg.result);}}
            else if(msg.method==='Runtime.exceptionThrown')this.errors.push(msg.params.exceptionDetails);
            else if(msg.method==='Runtime.consoleAPICalled'&&msg.params.type==='error')this.console.push(msg.params.args.map(a=>a.value||a.description).join(' '));};
    }
    async call(method,params={}){await this.ready;const id=++this.id;return new Promise((resolve,reject)=>{this.pending.set(id,{resolve,reject});this.ws.send(JSON.stringify({id,method,params}));});}
    async eval(expression){const r=await this.call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
}
(async()=>{
    await new Promise(r=>server.listen(0,'127.0.0.1',r));
    const url='http://127.0.0.1:'+server.address().port;
    const child=cp.spawn(edge,['--headless=new','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',
        '--user-data-dir='+profile,'--use-angle=swiftshader','--enable-unsafe-swiftshader','--autoplay-policy=no-user-gesture-required',
        '--window-size=1200,800','about:blank'],{stdio:['ignore','ignore','pipe']});
    let browserLog='';child.stderr.on('data',d=>browserLog+=d);
    let cdp;
    try{
        const portFile=path.join(profile,'DevToolsActivePort');
        for(let i=0;i<100&&!fs.existsSync(portFile);i++)await sleep(100);
        const port=fs.readFileSync(portFile,'utf8').split('\n')[0];
        const pages=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        cdp=new CDP(pages.find(p=>p.type==='page').webSocketDebuggerUrl);
        await cdp.call('Runtime.enable');await cdp.call('Page.enable');
        await cdp.call('Emulation.setFocusEmulationEnabled',{enabled:true});
        await cdp.call('Page.bringToFront');
        await cdp.call('Emulation.setUserAgentOverride',{userAgent:'Mozilla/5.0 (Linux; Android 14; PadPort) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'});
        await cdp.call('Page.navigate',{url});
        let state;
        for(let i=0;i<180;i++){
            await sleep(500);
            state=await cdp.eval(`({scene:window.SceneManager&&SceneManager._scene&&SceneManager._scene.constructor.name,started:window.SceneManager&&SceneManager._scene&&SceneManager._scene._started,focus:document.hasFocus(),error:typeof main!=='undefined'&&main.error&&String(main.error),printer:document.getElementById('errorPrinter')?.textContent})`);
            if(state.error||state.printer)throw Error(JSON.stringify(state));
            if(state.started && ['Scene_Title','Scene_Map'].includes(state.scene))break;
        }
        assert.ok(state.started && ['Scene_Title','Scene_Map'].includes(state.scene),'Game did not finish booting: '+JSON.stringify(state));
        await cdp.call('Page.captureScreenshot',{format:'png'}).then(x=>fs.writeFileSync(path.join(out,'look-outside-startup.png'),Buffer.from(x.data,'base64')));
        await cdp.eval(`__native={revision:2,paused:false,pads:[{id:'Native test pad',nativeId:7,index:0,axes:[-1,0,0,0],buttons:Array(17).fill(0)}]}`);
        await sleep(200);
        let input=await cdp.eval(`({left:Input.isPressed('left'),keyboardEvents:__keyEvents,mapping:navigator.getGamepads()[0].mapping,nwjs:Utils.isNwjs(),mobile:Utils.isMobileDevice()})`);
        assert.equal(input.left,true);assert.equal(input.keyboardEvents,0);assert.equal(input.mapping,'standard');assert.equal(input.nwjs,false);
        await cdp.eval(`__native.pads[0].axes=[0,0,0,0];__native.pads[0].buttons[3]=1;__native.revision++`);
        await sleep(150);
        let button=await cdp.eval(`({menu:Input.isPressed(Input.gamepadMapper[3]),apiPressed:navigator.getGamepads()[0].buttons[3].pressed,keyboardEvents:__keyEvents})`);
        assert.equal(button.apiPressed,true);assert.equal(button.menu,true);assert.equal(button.keyboardEvents,0);
        await cdp.eval(`__native={revision:4,paused:false,pads:[]}`);await sleep(100);
        const released=await cdp.eval(`({left:Input.isPressed('left'),menu:Input.isPressed('menu'),pads:navigator.getGamepads().filter(Boolean).length})`);
        assert.equal(released.left,false);assert.equal(released.menu,false);assert.equal(released.pads,0);
        const saves=await cdp.eval(`PadPort.collectSaves().then(s=>({format:s.format,local:Object.keys(s.local).length,forage:Object.keys(s.forage).length}))`);
        assert.equal(saves.format,'PadPort saves');
        const report={game,environment:'Desktop Edge, Android user-agent; not a physical Android/WebView test',state,input,button,released,saves,requests,missing,uncaughtErrors:cdp.errors,consoleErrors:cdp.console};
        fs.writeFileSync(path.join(out,'game-smoke.json'),JSON.stringify(report,null,2));
        assert.equal(cdp.errors.length,0,JSON.stringify(cdp.errors));
        console.log(JSON.stringify(report,null,2));
    }finally{
        if(cdp){await cdp.call('Browser.close').catch(()=>{});cdp.ws.close();}
        else child.kill();
        server.closeAllConnections();server.close();
        fs.writeFileSync(path.join(out,'browser.log'),browserLog);
        await sleep(1500);
        try{fs.rmSync(profile,{recursive:true,force:true});}catch{}
    }
})().catch(e=>{console.error(e);process.exitCode=1;});

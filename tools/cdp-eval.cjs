// Evaluate a diagnostic expression in the explicitly forwarded emulator WebView.
let source='';process.stdin.setEncoding('utf8');process.stdin.on('data',x=>source+=x);
process.stdin.on('end',async()=>{
    const timeout=setTimeout(()=>{console.error('CDP timeout');process.exit(1);},10000);
    try{
        const pages=await(await fetch('http://127.0.0.1:9224/json/list')).json();
        const page=pages.find(p=>p.type==='page'&&p.url.includes('padport.local'));
        if(!page)throw Error('No PadPort WebView: '+JSON.stringify(pages));
        const url=new URL(page.webSocketDebuggerUrl);url.hostname='127.0.0.1';url.port='9224';
        const ws=new WebSocket(url);
        ws.onopen=()=>ws.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression:source,returnByValue:true,awaitPromise:true}}));
        ws.onmessage=e=>{const msg=JSON.parse(e.data);if(msg.id!==1)return;
            clearTimeout(timeout);ws.close();
            if(msg.error||msg.result.exceptionDetails){console.error(JSON.stringify(msg));process.exitCode=1;}
            else console.log(JSON.stringify(msg.result.result.value??null));
        };
        ws.onerror=e=>{clearTimeout(timeout);console.error(e);process.exitCode=1;};
    }catch(e){clearTimeout(timeout);console.error(e);process.exitCode=1;}
});

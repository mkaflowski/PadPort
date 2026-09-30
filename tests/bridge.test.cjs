const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = ['ui-strings.js','bridge.js'].map(name=>fs.readFileSync(path.join(__dirname, '../app/src/main/assets',name), 'utf8')).join('\n');

// Game files as the PadPort server serves them: case-insensitive, 404 when missing.
function fakeServer(files) {
    const requests=[];
    const lookup=url=>{
        const rel=decodeURIComponent(new URL(url).pathname.slice(1)).toLowerCase();
        for(const [name,data] of Object.entries(files)) if(name.toLowerCase()===rel) return data;
        return null;
    };
    class XMLHttpRequest {
        open(method,url,async){this.method=method;this.url=url;this.async=async;}
        overrideMimeType(m){this.mime=m;}
        send(){
            requests.push(this.method+' '+this.url);
            if(this.async!==false) throw Error('fs must use synchronous requests');
            const data=lookup(this.url);
            this.status=data==null?404:200;
            this.responseText=data==null?'Not found':(this.method==='HEAD'?'':
                (this.mime&&this.mime.includes('x-user-defined')?Array.from(data,b=>String.fromCharCode(b|0xf700)).join(''):Buffer.from(data).toString('utf8')));
            this.length=data==null?9:data.length;
        }
        getResponseHeader(h){return h.toLowerCase()==='content-length'?`${this.length}, ${this.length}`:null;}
    }
    return {XMLHttpRequest,requests};
}

function setup(language, files={}) {
    let current={revision:1,paused:false,pads:[null,null,null,null]};
    const events=[], local=new Map(), forage=new Map(), listeners=new Map();
    let clears=0;
    const server=fakeServer(files);
    const sandbox={console, Event:class Event{constructor(type){this.type=type;}}, Blob, ArrayBuffer, Uint8Array, URL, setTimeout,
        XMLHttpRequest:server.XMLHttpRequest, document:{baseURI:'https://g1.padport.local/index.html'},
        performance:{now:()=>42}, navigator:{}, __PADPORT_CONFIG__:{title:'Test Game',engine:'MZ',language},
        localStorage:{get length(){return local.size;}, key:i=>[...local.keys()][i],getItem:k=>local.get(k)??null,
            setItem:(k,v)=>local.set(k,v),removeItem:k=>local.delete(k)},
        localforage:{ready:async()=>{},keys:async()=>[...forage.keys()],getItem:async k=>forage.get(k),
            setItem:async(k,v)=>forage.set(k,v),removeItem:async k=>forage.delete(k)},
        Input:{clear(){clears++;}}, TouchInput:{clear(){}},
        PadPortHost:{snapshot:()=>JSON.stringify(current),log(){},saveError(){},importReady(){}},
        requestAnimationFrame(){},addEventListener(type,callback){if(!listeners.has(type))listeners.set(type,[]);listeners.get(type).push(callback);},dispatchEvent:e=>events.push(e)};
    sandbox.window=sandbox;
    vm.createContext(sandbox);vm.runInContext(source,sandbox);
    return {sandbox,events,local,forage,requests:server.requests,clears:()=>clears,set:x=>current=x,fire:type=>(listeners.get(type)||[]).forEach(fn=>fn({type}))};
}
function pad(id=7,buttons=[]) {return {nativeId:id,id:'Xbox test',index:0,axes:[.75,-1,.2,.4],buttons:Array.from({length:17},(_,i)=>buttons[i]||0)};}

test('native buttons and analog values are Gamepad objects, with zero keyboard events',()=>{
    const t=setup();t.set({revision:2,paused:false,pads:[pad(7,{0:1,6:.8}),null,null,null]});
    const p=t.sandbox.navigator.getGamepads()[0];
    assert.equal(p.mapping,'standard');assert.equal(p.buttons[0].pressed,true);
    assert.equal(p.buttons[6].value,.8);assert.equal(p.axes[0],.75);
    assert.deepEqual(t.events.map(x=>x.type),['gamepadconnected']);
});
test('release, disconnect and slot reuse cannot leave a stuck direction',()=>{
    const t=setup();t.set({revision:2,pads:[pad(7,{12:1})]});
    const old=t.sandbox.navigator.getGamepads()[0];
    t.set({revision:3,pads:[null]});assert.equal(t.sandbox.navigator.getGamepads()[0],null);
    assert.equal(old.connected,false);assert.equal(old.buttons[12].pressed,false);assert.equal(t.clears(),1);
    t.set({revision:4,pads:[pad(8,{1:1})]});assert.equal(t.sandbox.navigator.getGamepads()[0]._nativeId,8);
    assert.deepEqual(t.events.map(x=>x.type),['gamepadconnected','gamepaddisconnected','gamepadconnected']);
});
test('pausing releases buttons and axes even when Android still reports held input',()=>{
    const t=setup();t.set({revision:2,pads:[pad(7,{0:1})],paused:true});
    const p=t.sandbox.navigator.getGamepads()[0];assert.equal(p.buttons[0].value,0);assert.equal(p.axes[0],0);assert.equal(t.clears(),1);
});
test('RPG Maker remains in browser storage mode',()=>{
    const t=setup();assert.equal(typeof t.sandbox.process,'undefined');assert.equal(typeof t.sandbox.nw,'undefined');
    assert.equal(t.sandbox.require('os').userInfo().username,'Player');
    assert.throws(()=>t.sandbox.require('child_process'),/module is unavailable/);
});
test('Look Outside monsterImageExists() works: read-only fs over the game server',()=>{
    const t=setup('en',{'img/enemies/EyeZombies/Zombie_KnifeEye_Far.png_':[1,2,3,4]});
    // verbatim from Look Outside js/plugins/bunchastuff.js - crashed in battle before
    const monsterImageExists=vm.runInContext(`(function monsterImageExists(filename){
        var fs = require ("fs");
        var foundit = fs.existsSync("./img/enemies/" + filename+".png");
        foundit = foundit || fs.existsSync("./img/enemies/" + filename+".png_");
        return foundit;
    })`,t.sandbox);
    assert.equal(monsterImageExists('EyeZombies/Zombie_KnifeEye_Far'),true);
    assert.equal(monsterImageExists('EyeZombies/Zombie_KnifeEye_Close'),false);
    const before=t.requests.length;
    monsterImageExists('EyeZombies/Zombie_KnifeEye_Far');monsterImageExists('EyeZombies/Zombie_KnifeEye_Close');
    assert.equal(t.requests.length,before,'answers are cached for the session');
    assert.ok(t.requests.every(r=>r.startsWith('HEAD https://g1.padport.local/img/enemies/')));
});
test('fs: case-insensitive names, stat, text and binary reads, encoded paths, node: prefix',()=>{
    const t=setup('en',{'data/System.json':[...Buffer.from('{"t":"żółw"}')],'img/pictures/a b#1.png':[0,127,128,255]});
    const fs=t.sandbox.require('node:fs');
    assert.equal(fs.existsSync('DATA\\system.JSON'),true);
    assert.equal(fs.statSync('data/System.json').size,Buffer.from('{"t":"żółw"}').length);
    assert.equal(fs.statSync('nope.txt',{throwIfNoEntry:false}),undefined);
    assert.throws(()=>fs.statSync('nope.txt'),e=>e.code==='ENOENT');
    assert.equal(JSON.parse(fs.readFileSync('./data/System.json','utf8')).t,'żółw');
    assert.deepEqual(Array.from(fs.readFileSync('img/pictures/a b#1.png')),[0,127,128,255]);
    assert.ok(t.requests.some(r=>r.endsWith('/img/pictures/a%20b%231.png')));
    assert.throws(()=>fs.readFileSync('missing.png'),e=>e.code==='ENOENT');
});
test('fs refuses writes with a localized message; saves stay in browser storage',()=>{
    const en=setup('en'),pl=setup('pl');
    assert.throws(()=>en.sandbox.require('fs').writeFileSync('save/file1.rmmzsave','x'),/read-only/);
    assert.throws(()=>pl.sandbox.require('fs').mkdirSync('save'),/tylko do odczytu/);
    assert.equal(typeof en.sandbox.process,'undefined');
});
test('path helpers used next to fs',()=>{
    const p=setup().sandbox.require('path');
    assert.equal(p.join('img/','enemies','a.png'),'img/enemies/a.png');
    assert.equal(p.dirname('img/enemies/a.png'),'img/enemies');
    assert.equal(p.basename('img/enemies/a.png','.png'),'a');
    assert.equal(p.extname('a.png_'),'.png_');
});
test('save export/import round trip covers MV localStorage and MZ localforage',async()=>{
    const t=setup();t.local.set('RPG File1','żółw\\n😀');t.forage.set('rmmzsave.123.file1','compressed==');
    const backup=await t.sandbox.PadPort.collectSaves();t.local.clear();t.forage.clear();
    await t.sandbox.PadPort.importSaves(backup);
    assert.equal(t.local.get('RPG File1'),'żółw\\n😀');assert.equal(t.forage.get('rmmzsave.123.file1'),'compressed==');
    await assert.rejects(t.sandbox.PadPort.importSaves({...backup,title:'Other game'}),/another game/);
});
test('a failed import restores the slots already written',async()=>{
    const t=setup();t.local.set('slot','original');t.forage.set('slot','old');
    const save=t.sandbox.localforage.setItem;
    t.sandbox.localforage.setItem=async(k,v)=>{if(v==='FAIL')throw Error('disk');return save(k,v);};
    await assert.rejects(t.sandbox.PadPort.importSaves({format:'PadPort saves',version:1,title:'Test Game',engine:'MZ',local:{slot:'new'},forage:{slot:'FAIL'}}));
    assert.equal(t.local.get('slot'),'original');assert.equal(t.forage.get('slot'),'old');
});

function graphics(t,width=816,height=624) {
    let updates=0;
    const g={_width:width,_height:height,_canvas:{},_stretchEnabled:false,_realScale:1,
        _updateRealScale(){this._realScale=.9;},
        _updateAllElements(){updates++;this._updateRealScale();this.drawWidth=this._width*this._realScale;this.drawHeight=this._height*this._realScale;}};
    t.sandbox.Graphics=g;
    return {g,updates:()=>updates};
}
test('game fills the available height without MZ mobile padding or aspect distortion',()=>{
    const t=setup(),{g,updates}=graphics(t);
    Object.assign(t.sandbox,{innerWidth:1920,innerHeight:1080});
    assert.equal(t.sandbox.PadPort.fitToViewport(),true);
    assert.equal(g.drawHeight,1080);
    assert.ok(g.drawWidth<1920);
    assert.equal(g.drawWidth/g.drawHeight,816/624);
    assert.equal(g._width,816);assert.equal(g._height,624);
    for(let i=0;i<10;i++)t.sandbox.navigator.getGamepads();
    assert.equal(updates(),1,'unchanged frames must not reset/reallocate the canvas');
});
test('fit scales up/down and reacts to viewport or game-resolution changes',()=>{
    const t=setup(),{g}=graphics(t,1280,720);
    for(const [width,height] of [[1920,1080],[640,480],[800,1280]]) {
        Object.assign(t.sandbox,{innerWidth:width,innerHeight:height});
        t.sandbox.PadPort.fitToViewport();
        assert.equal(g._realScale,Math.min(width/1280,height/720));
        assert.ok(g.drawWidth<=width+1e-9 && g.drawHeight<=height+1e-9);
        assert.ok(Math.abs(g.drawWidth-width)<1e-9 || Math.abs(g.drawHeight-height)<1e-9);
    }
    g._width=640;g._height=480;t.sandbox.PadPort.fitToViewport();
    assert.equal(g.drawWidth,800);assert.equal(g.drawHeight,600);
});
test('late plugin integer scaling cannot reintroduce unused screen margins',()=>{
    const t=setup(),{g}=graphics(t);
    Object.assign(t.sandbox,{innerWidth:1920,innerHeight:1080});
    t.sandbox.PadPort.fitToViewport();
    g._updateRealScale=function(){this._realScale=1.5;};g._updateAllElements();
    t.sandbox.PadPort.fitToViewport();assert.equal(g.drawHeight,1080);
});
test('English is the default WebView language; Polish localizes messages',()=>{
    const english=setup(),polish=setup('pl'),unknown=setup('fr');
    assert.equal(english.sandbox.PadPortI18n.language,'en');
    assert.equal(unknown.sandbox.PadPortI18n.language,'en');
    assert.equal(english.sandbox.PadPortI18n.text('tester_heading'),'What does the game see?');
    assert.equal(polish.sandbox.PadPortI18n.text('tester_heading'),'Co widzi gra?');
    assert.throws(()=>polish.sandbox.require('child_process'),/niedostępny moduł/);
});
test('changing app language preserves the backup format and game identity',async()=>{
    const english=setup('en'),polish=setup('pl');english.local.set('RPG File1','existing save');
    const backup=await english.sandbox.PadPort.collectSaves();
    await polish.sandbox.PadPort.importSaves(backup);
    assert.equal(polish.local.get('RPG File1'),'existing save');
    assert.equal(backup.format,'PadPort saves');
});
test('keyboard mode hides gamepads so a press is not delivered twice',()=>{
    const t=setup();t.set({revision:2,inputMode:'keyboard',paused:false,pads:[pad(7,{0:1})]});
    assert.equal(t.sandbox.navigator.getGamepads().filter(Boolean).length,0);
    assert.equal(t.sandbox.PadPort.inputMode(),'keyboard');
    assert.equal(t.events.length,0,'native WebView events, not this bridge, deliver keyboard input');
});
test('switching modes disconnects the old gamepad before the first keyboard listener',()=>{
    const t=setup();t.set({revision:2,inputMode:'gamepad',pads:[pad(7,{0:1})]});
    t.sandbox.navigator.getGamepads();
    t.set({revision:3,inputMode:'keyboard',pads:[pad(7,{0:1})]});t.fire('keydown');
    assert.equal(t.sandbox.PadPort.inputMode(),'keyboard');
    assert.deepEqual(t.events.map(e=>e.type),['gamepadconnected','gamepaddisconnected']);
    assert.equal(t.sandbox.navigator.getGamepads().filter(Boolean).length,0);
    t.set({revision:4,inputMode:'gamepad',pads:[pad(7,{0:1})]});
    assert.equal(t.sandbox.navigator.getGamepads()[0].buttons[0].pressed,true);
});

// Per-game runtime fixes (assets/game-compat.js) for Welcome to Elderfield.
//   node --test tests/game-compat.test.cjs
//   ELDERFIELD_GAME="F:\DepotDownloaderMod\Welcome to Elderfield" node --test tests/game-compat.test.cjs
const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets');
const source=['ui-strings.js','bridge.js','game-compat.js'].map(n=>fs.readFileSync(path.join(assets,n),'utf8')).join('\n');
const GAME=process.env.ELDERFIELD_GAME;

function setup(config={},files={}) {
    const listeners=new Map(),requests=[],logs=[];
    const find=url=>{
        const rel=decodeURIComponent(new URL(url,'https://g1.padport.local/').pathname.slice(1)).toLowerCase();
        for(const [name,data] of Object.entries(files)) if(name.toLowerCase()===rel) return data;
        return null;
    };
    class XMLHttpRequest {
        open(method,url,async){this.method=method;this.url=url;this.async=async;}
        overrideMimeType(){}
        send(){requests.push(this.method+' '+this.url);const d=find(this.url);this.status=d==null?404:200;this.responseText=d==null?'':String(d);}
        getResponseHeader(){return null;}
    }
    const fetch=async url=>{requests.push('GET '+url);const d=find(url);return {ok:d!=null,status:d==null?404:200,text:async()=>String(d)};};
    const sandbox={console,URL,setTimeout,XMLHttpRequest,fetch,document:{baseURI:'https://g1.padport.local/index.html'},
        performance:{now:()=>1},navigator:{},localStorage:{length:0,key(){},getItem(){return null},setItem(){},removeItem(){}},
        __PADPORT_CONFIG__:{title:'Welcome to Elderfield',engine:'MZ',language:'en',...config},
        PadPortHost:{snapshot:()=>JSON.stringify({revision:1,pads:[]}),log:m=>logs.push(m)},
        requestAnimationFrame(){},addEventListener(type,fn){if(!listeners.has(type))listeners.set(type,[]);listeners.get(type).push(fn);},dispatchEvent(){}};
    sandbox.window=sandbox;vm.createContext(sandbox);vm.runInContext(source,sandbox);
    return {sandbox,requests,logs,fire:type=>(listeners.get(type)||[]).forEach(fn=>fn({type})),run:code=>vm.runInContext(code,sandbox)};
}
const elderfield=(files={},tables=Object.keys(files).filter(f=>/^tables\//i.test(f)))=>
    setup({compat:'welcome-to-elderfield',nodeShim:false,tables},files);
const settle=()=>new Promise(r=>setTimeout(r,0));

test('other games keep the read-only Node shim and get no compat hooks',()=>{
    const t=setup();
    assert.equal(typeof t.sandbox.require,'function');
    assert.equal(t.sandbox.PadPortCompat,undefined);
    t.run('window.TableManager={x:1}');assert.equal(t.sandbox.TableManager.x,1);
});
test('Elderfield: no require, so Steam and plugin-toggle code take their browser path',()=>{
    const t=elderfield();
    assert.equal(typeof t.sandbox.require,'undefined');assert.equal(typeof t.sandbox.process,'undefined');
    // The load-time guards used by Cyclone-Steam (typeof require !== 'function') and
    // WTE_PluginToggleManager (typeof require !== 'undefined') now skip process.*.
    assert.equal(t.run(`typeof require !== 'function'`),true);
    assert.equal(t.run(`typeof require !== 'undefined'`),false);
});
// Same contract as LookupTableComparison: browser path only fetches its (empty) preload list.
const TABLE_PLUGIN=`(() => { const preloadList=[];
  window.TableManager={cacheString:{},cacheNum:{},_webFilesLoaded:0,_webFilesTotal:0,
    preloadAll(){ this._webFilesTotal=preloadList.length; },
    parseAndCache(key,content){ this.cacheString[key]=content.split(/\\r?\\n/).map(l=>l.trim()).filter(Boolean).map(l=>l.replace(/^"(.*)"$/,'$1')); } };
  window.isLoaded=()=>TableManager._webFilesLoaded>=TableManager._webFilesTotal;
  window.string={CSV:f=>TableManager.cacheString[f.replace(/\\\\/g,'/')]||[]}; })();`;
test('gift tables from PadPort\'s file index load under the NW.js keys before the database is ready',async()=>{
    const t=elderfield({'Tables/Gifts/AliceLoves.csv':'"Wheat"\r\nBitter Herb\r\n','Tables/Gifts/UniversalLikes.csv':'Coffee'});
    t.run(TABLE_PLUGIN);t.run('TableManager.preloadAll()');
    assert.equal(t.run('isLoaded()'),false,'boot waits for the tables');
    await settle();await settle();
    assert.equal(t.run('isLoaded()'),true);
    assert.deepEqual(JSON.parse(t.run('JSON.stringify(string.CSV("Gifts/AliceLoves"))')),['Wheat','Bitter Herb']);
    assert.deepEqual(JSON.parse(t.run('JSON.stringify(string.CSV("Gifts/UniversalLikes"))')),['Coffee']);
});
test('a missing table cannot block the boot',async()=>{
    const t=elderfield({},['Tables/Gifts/Gone.csv']);
    t.run(TABLE_PLUGIN);t.run('TableManager.preloadAll()');await settle();await settle();
    assert.equal(t.run('isLoaded()'),true);assert.equal(t.run('string.CSV("Gifts/Gone").length'),0);
    assert.ok(t.logs.some(m=>/table Tables\/Gifts\/Gone\.csv/.test(m)));
});
test('music player song check works without Node; other scenes are untouched',()=>{
    const t=elderfield({'media/player/7.ogg':'OggS'});
    t.run(`class Scene_MusicPlayer { isSongFileExists(p){ return require('fs').existsSync(p) && process.mainModule; } }
           class Scene_Map { isSongFileExists(){ return 'map'; } }
           window.SceneManager={_scene:null,created:0,onSceneCreate(){this.created++;}};`);
    t.fire('load');
    t.run('SceneManager._scene=new Scene_Map();SceneManager.onSceneCreate()');
    assert.equal(t.run('SceneManager._scene.isSongFileExists()'),'map');
    t.run('SceneManager._scene=new Scene_MusicPlayer();SceneManager.onSceneCreate()');
    assert.equal(t.run('SceneManager.created'),2,'original onSceneCreate still runs');
    assert.equal(t.run(`SceneManager._scene.isSongFileExists('media/player/7.ogg')`),true);
    assert.equal(t.run(`SceneManager._scene.isSongFileExists('media/player/99.ogg')`),false);
    t.run(`SceneManager._scene.isSongFileExists('media/player/7.ogg')`);
    assert.equal(t.requests.filter(r=>r.endsWith('media/player/7.ogg')).length,1,'probe is cached');
});

const real=f=>fs.readFileSync(path.join(GAME,f),'utf8');
test('real game: plugin code still matches the assumptions of these fixes',{skip:!GAME},()=>{
    const steam=real('js/plugins/Cyclone-Steam.js');
    assert.ok(steam.indexOf(`typeof require !== 'function'`)<steam.indexOf('process.env.SteamAppId'));
    assert.match(real('js/plugins/WTE_PluginToggleManager.js'),/if \(typeof require !== 'undefined'\) \{\s*const fs = require\('fs'\);\s*const path = require\('path'\);\s*const base = path\.dirname\(process\.mainModule\.filename\)/);
    const music=real('js/plugins/SimpleMusicPlayer.js');
    assert.match(music,/class Scene_MusicPlayer extends/);assert.match(music,/isSongFileExists\(filePath\) \{/);
    assert.match(music,/using relative/,'playback falls back to a relative path');
    assert.match(real('js/plugins.js'),/"name":"LookupTableComparison","status":true[^\n]*"preloadFiles":"\[\]"/);
});
test('real game: LookupTableComparison loads every Tables/ CSV through the compat hook',{skip:!GAME},async()=>{
    const files={};
    const walk=dir=>{for(const e of fs.readdirSync(path.join(GAME,dir),{withFileTypes:true})){
        const rel=dir+'/'+e.name; if(e.isDirectory()) walk(rel); else if(/\.csv$/i.test(e.name)) files[rel]=fs.readFileSync(path.join(GAME,rel),'utf8');}};
    walk('Tables');
    const t=elderfield(files);
    t.run(`window.PluginManager={parameters:()=>({preloadFiles:'[]'})};window.Utils={isNwjs:()=>false};
           window.DataManager={loadDatabase(){},isDatabaseLoaded(){return true;}};`);
    t.run(real('js/plugins/LookupTableComparison.js'));
    t.run('DataManager.loadDatabase()');
    assert.equal(t.run('DataManager.isDatabaseLoaded()'),false);
    for(let i=0;i<5;i++) await settle();
    assert.equal(t.run('DataManager.isDatabaseLoaded()'),true);
    assert.equal(t.run('Object.keys(TableManager.cacheString).length'),Object.keys(files).length);
    const loves=t.run('string.CSV("Gifts/AliceLoves")');
    assert.ok(loves.length>0,'Alice has loved gifts');
    const problems=t.logs.filter(m=>/^compat:/.test(m));
    assert.equal(problems.length,0,problems.join('\n'));
});

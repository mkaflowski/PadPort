// Welcome to Elderfield (MZ) dual-screen profile: in-game adapter + lower-screen panel.
//   node --test tests/elderfield-dual.test.cjs
//   ELDERFIELD_GAME="F:\DepotDownloaderMod\Welcome to Elderfield" node --test tests/elderfield-dual.test.cjs
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets');
const adapter=fs.readFileSync(path.join(assets,'elderfield-dual.js'),'utf8');
const GAME=process.env.ELDERFIELD_GAME;
const plain=v=>JSON.parse(JSON.stringify(v));

function windowStub(extra={}){
    const w={active:false,visible:true,openness:255,_index:-1,calls:[],
        activate(){this.active=true;this.calls.push('activate');},deactivate(){this.active=false;this.calls.push('deactivate');},
        select(i){this._index=i;this.calls.push('select:'+i);},index(){return this._index;},
        processOk(){this.calls.push('ok');this.onOk?.();},processCancel(){this.calls.push('cancel');this.onCancel?.();},...extra};
    return w;
}
function game(profile='welcome-to-elderfield'){
    let control={active:true,epoch:2,commands:[]},state=null,sent=0;
    const items={7:{id:7,name:'Bitter Herb',description:'\\c[3]Bitter\\c[0] herb.<br>Restores HP.',iconIndex:1739,note:'<actions>\nUse (Eat)\n</actions>'},
        8:{id:8,name:'Dough',description:'Dough.',iconIndex:900,note:'<actions></actions>'},
        30:{id:30,name:'Extra Pouch',description:'More room.',iconIndex:5,note:'<actions>\nUse\nDrop (Discard)\n</actions>'}};
    const states=[{id:243,name:'Coffee',iconIndex:6248},{id:1,name:'Dead',iconIndex:0}];
    const actor={actorId:()=>1,name:()=>' Ann',level:4,hp:30,mhp:40,mp:5,mmp:10,faceName:()=>'Actor1',faceIndex:()=>0,
        states:()=>states,equips:()=>[{name:'Old Hoe',iconIndex:1800},null]};
    const vars={1618:'9:00 AM',1619:'Mon 1, Rebirth',1475:75,123:2,127:9,128:0,125:1,243:2},sw={};
    class Scene_Base{isActive(){return true;}}
    class Scene_Map extends Scene_Base{isBusy(){return false;}isMenuEnabled(){return true;}updateCallMenu(){}}
    class Scene_Battle extends Scene_Base{} class Scene_Title extends Scene_Base{} class Scene_Item extends Scene_Base{}
    function Scene_SplashFlipbook(){}
    const pushes=[];
    const translations={'Bitter Herb':'Bitterkraut','Energy':'Energie','Eat':'Essen'};
    const sandbox={console,setInterval(){},clearInterval(){},addEventListener(){},location:{href:'https://g1.padport.local/index.html'},URL,
        document:{baseURI:'https://g1.padport.local/index.html',createElement:()=>({width:0,height:0,getContext:()=>({drawImage(){}}),toDataURL:()=>'data:image/png;base64,QQ=='})},
        __PADPORT_CONFIG__:{dualScreenProfile:profile},
        WTE_Translate:s=>translations[s]||s,
        Scene_Base,Scene_Map,Scene_Battle,Scene_Title,Scene_Item,Scene_SplashFlipbook,
        DataManager:{isItem:i=>!!items[i?.id]&&i===items[i.id],isWeapon:()=>false},
        TextManager:{item:'Items',hpA:'HP',mpA:'MP',levelA:'Lv',currencyUnit:'$'},
        ImageManager:{iconWidth:32,iconHeight:32,faceWidth:144,faceHeight:144,loadSystem:()=>({isReady:()=>true,canvas:{}}),loadFace:()=>sandbox.face},
        face:{isReady:()=>true,canvas:{}},
        FontManager:{_urls:{'rmmz-mainfont':'fonts/VCR.woff'}},
        $gameSystem:{mainFontFace:()=>'rmmz-mainfont, Arial'},
        $gameParty:{members:()=>[actor],size:()=>1,gold:()=>120,numItems:i=>i.id===7?3:1},
        $gameVariables:{value:id=>vars[id]??0},$gameSwitches:{value:id=>!!sw[id]},
        $gameMap:{isEventRunning:()=>false,mapId:()=>5},$gameMessage:{isBusy:()=>false},$gamePlayer:{isMoving:()=>false},
        $gameTemp:{clearDestination(){}},SoundManager:{playOk(){}},
        $gameContainers:{getCurrentPartyInventoryWeight:()=>9,getCurrentPartyInventoryMaxWeight:()=>14},
        SceneManager:{_scene:new Scene_Map(),isSceneChanging:()=>false,push(c){pushes.push(c);}},
        PadPortHost:{dualPoll:()=>JSON.stringify(control),dualSnapshot:s=>{state=JSON.parse(s);sent++;},snapshot:()=>'{"paused":false}',log(){}}};
    sandbox.window=sandbox;vm.createContext(sandbox);vm.runInContext(adapter,sandbox);
    function itemScene(){
        const scene=new Scene_Item();
        scene._categoryWindow=windowStub({_list:[{symbol:'items',name:'Items',enabled:true},{symbol:'keyitems',name:'Key Items',enabled:true}],
            currentSymbol(){return this._list[Math.max(0,this._index)].symbol;}});
        scene._categoryWindow.active=true;scene._categoryWindow._index=0;
        scene._itemWindow=windowStub({_data:[items[7],items[8],items[30],{id:-999,isDummyItem:true}],
            item(){return this._data[this._index];},setCategory(s){this.calls.push('category:'+s);}});
        scene._itemActionWindow=windowStub({visible:false,_list:[{name:'Use',symbol:'use'},{name:'Drop',symbol:'drop'},{name:'Cancel',symbol:'back'}]});
        scene._actorWindow=windowStub({visible:false,cursorFixed:()=>false,cursorAll:()=>false});
        // Generic action window, as DM_LimitedInventory opens it for items without a single action.
        scene._itemWindow.onOk=()=>{scene._itemWindow.active=false;Object.assign(scene._itemActionWindow,{visible:true,active:true});};
        scene._itemActionWindow.onCancel=()=>{Object.assign(scene._itemActionWindow,{visible:false,active:false});scene._itemWindow.active=true;};
        return scene;
    }
    return {sandbox,items,actor,vars,sw,pushes,itemScene,tick:()=>sandbox.ElderfieldDual.tick(),get state(){return state;},get sent(){return sent;},
        send:(...commands)=>{control={...control,commands};},setControl:c=>{control=c;}};
}

test('only Welcome to Elderfield gets the adapter',()=>{
    assert.equal(game('look-outside').sandbox.ElderfieldDual,undefined);
    assert.ok(game().sandbox.ElderfieldDual);
});
test('title and the splash flipbook show no card; the map shows the character, clock, weather and gold',async()=>{
    const t=game();
    t.sandbox.SceneManager._scene=new t.sandbox.Scene_Title();await t.tick();assert.equal(t.state.mode,'waiting');
    t.sandbox.SceneManager._scene=new t.sandbox.Scene_SplashFlipbook();await t.tick();assert.equal(t.state.mode,'waiting');
    t.sandbox.SceneManager._scene=new t.sandbox.Scene_Map();await t.tick();
    const s=t.state;assert.equal(s.mode,'party');assert.equal(s.canOpen,true);
    const me=s.party[0];assert.equal(me.name,'Ann');assert.equal(me.level,4);assert.equal(me.hp,30);
    assert.deepEqual(me.states.map(x=>x.name),['Coffee'],'icon-less states are hidden');
    assert.deepEqual(me.equipment.map(x=>x.name),['Old Hoe']);
    assert.match(me.portrait,/^face:Actor1:0:\d+$/);assert.ok(s.images[me.portrait].startsWith('data:image/png'));
    assert.deepEqual(plain(s.hud),{time:'9:00 AM',date:'Mon 1, Rebirth',gold:120,currency:'$',energy:75,energyLabel:'Energie',
        sky:{hour:9,minute:0,phase:2,season:1,night:false,weather:'rain'}});
    assert.deepEqual(plain(s.fonts),['fonts/VCR.woff']);
});
test('weather follows the game\'s variable and storm/snow switches',async()=>{
    const t=game();const weather=async()=>{await t.tick();return t.state.hud.sky.weather;};
    t.vars[243]=1;assert.equal(await weather(),'clear');
    t.vars[243]=3;assert.equal(await weather(),'cloudy');
    t.vars[243]=4;assert.equal(await weather(),'storm');
    t.vars[243]=1;t.sw[1123]=true;assert.equal(await weather(),'snow');
});
test('a new character-creator face (new composite bitmap) gives a new portrait key',async()=>{
    const t=game();await t.tick();const first=t.state.party[0].portrait;
    t.sandbox.face={isReady:()=>true,canvas:{}};await t.tick();
    assert.notEqual(t.state.party[0].portrait,first);
});
test('the inventory button opens the game\'s own Scene_Item from the map',async()=>{
    const t=game();await t.tick();
    t.send({action:'inventory',epoch:2,scene:t.state.scene});await t.tick();
    assert.equal(t.pushes.length,1);assert.equal(t.pushes[0],t.sandbox.Scene_Item);
    t.sandbox.$gameMap.isEventRunning=()=>true;t.send({action:'inventory',epoch:2,scene:t.state.scene});await t.tick();
    assert.equal(t.pushes.length,1,'not during events');
});
test('inventory mirror: categories, translated items, action labels, slots; placeholder filtered',async()=>{
    const t=game();await t.tick();const scene=t.itemScene();t.sandbox.SceneManager._scene=scene;await t.tick();
    const s=t.state;assert.equal(s.mode,'inventory');
    assert.deepEqual(s.categories.map(c=>c.key),['items','keyitems']);assert.equal(s.category,'items');
    assert.deepEqual(s.items.map(i=>[i.key,i.name,i.count,i.action,i.usable]),
        [['item:7','Bitterkraut',3,'Essen',true],['item:8','Dough',1,'',false],['item:30','Extra Pouch',1,'',true]]);
    assert.equal(s.items[0].description,'Bitter herb.\nRestores HP.','escape codes stripped, <br> is a new line');
    assert.deepEqual(plain(s.slots),[9,14]);assert.equal(s.selected,'');assert.equal(s.canUse,false);
});
test('panel commands drive the game windows in the game\'s own order',async()=>{
    const t=game();await t.tick();const scene=t.itemScene();t.sandbox.SceneManager._scene=scene;await t.tick();
    const id=t.state.scene,cw=scene._categoryWindow,iw=scene._itemWindow;
    t.send({action:'category',key:'keyitems',epoch:2,scene:id});await t.tick();
    assert.deepEqual(cw.calls.slice(-2),['activate','select:1']);assert.equal(iw.calls.at(-1),'category:keyitems');
    t.send({action:'select',key:'item:30',epoch:2,scene:id});await t.tick();
    assert.equal(cw.active,false);assert.equal(iw.active,true);assert.equal(iw.index(),2);
    assert.equal(t.state.selected,'item:30');assert.equal(t.state.canUse,true);
    t.send({action:'use',key:'item:7',epoch:2,scene:id});await t.tick();
    assert.ok(!iw.calls.includes('ok'),'use must match the selected item');
    t.send({action:'use',key:'item:30',epoch:2,scene:id});await t.tick();
    assert.ok(iw.calls.includes('ok'));
    const popup=t.state.popup;assert.equal(popup.kind,'actions');
    assert.deepEqual(popup.choices.map(c=>c.name),['Use','Drop','Cancel']);assert.equal(t.state.canUse,false);
    t.send({action:'select',key:'item:7',epoch:2,scene:id});await t.tick();
    assert.equal(iw.index(),2,'the open popup owns the input');
    t.send({action:'choice',index:1,epoch:2,scene:id});await t.tick();
    assert.deepEqual(scene._itemActionWindow.calls.slice(-2),['select:1','ok']);
    t.send({action:'back',epoch:2,scene:id});await t.tick();
    assert.ok(scene._itemActionWindow.calls.includes('cancel'));assert.equal(t.state.popup,undefined);
    t.send({action:'back',epoch:2,scene:id});await t.tick();
    assert.ok(iw.calls.includes('cancel'),'back from the list asks the game to close the menu');
});
test('stale epoch or scene commands are ignored',async()=>{
    const t=game();await t.tick();const scene=t.itemScene();t.sandbox.SceneManager._scene=scene;await t.tick();
    t.send({action:'category',key:'keyitems',epoch:1,scene:t.state.scene},{action:'category',key:'keyitems',epoch:2,scene:999});await t.tick();
    assert.ok(!scene._categoryWindow.calls.includes('select:1'));
});

// ---------------------------------------------------------------------------------------------
class Element{
    constructor(tag){this.tagName=tag;this.children=[];this._text='';this.hidden=false;this.parentNode=null;this.className='';this.disabled=false;
        this.attributes=new Map();this.dataset={};this.listeners={};this.nodeType=1;
        const props=new Map();this.style={setProperty:(k,v)=>props.set(k,String(v)),getPropertyValue:k=>props.get(k)||'',removeProperty:k=>props.delete(k),width:''};
        const classes=new Set();this.classList={add:c=>classes.add(c),remove:c=>classes.delete(c),contains:c=>classes.has(c),toggle:(c,on)=>on?classes.add(c):classes.delete(c)};}
    get childNodes(){return this.children;} get firstChild(){return this.children[0]||null;} get offsetWidth(){return 1;}
    set textContent(v){this.replaceChildren();this._text=String(v);} get textContent(){return this._text+this.children.map(c=>c.textContent).join('');}
    append(...n){for(const x of n)this.insertBefore(x,null);} prepend(...n){for(const x of n.reverse())this.insertBefore(x,this.children[0]||null);}
    replaceChildren(...n){for(const c of this.children)c.parentNode=null;this.children=[];this._text='';this.append(...n);}
    insertBefore(n,b){if(n===b)return;n.remove?.();const i=b?this.children.indexOf(b):this.children.length;this.children.splice(i<0?this.children.length:i,0,n);n.parentNode=this;}
    remove(){if(this.parentNode){const s=this.parentNode.children;s.splice(s.indexOf(this),1);this.parentNode=null;}}
    setAttribute(k,v){this.attributes.set(k,String(v));} addEventListener(type,fn){this.listeners[type]=fn;}
    all(){return [this,...this.children.flatMap(c=>c.all?c.all():[])];}
}
class TextNode{constructor(t){this.nodeType=3;this._t=String(t);this.parentNode=null;} get textContent(){return this._t;} set textContent(v){this._t=String(v);} remove(){if(this.parentNode){const s=this.parentNode.children;s.splice(s.indexOf(this),1);this.parentNode=null;}}}
function panel(){
    const elements={},sent=[];
    const body=new Element('body');
    const context={console,__PADPORT_CONFIG__:{language:'pl'},matchMedia:()=>({matches:false}),
        document:{documentElement:new Element('html'),body,createElement:t=>new Element(t),createTextNode:t=>new TextNode(t),
            getElementById:id=>elements[id]||(elements[id]=new Element('div'))},
        GameCompanionHost:{ready(){},command:j=>sent.push(JSON.parse(j))}};
    context.window=context;vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(assets,'ui-strings.js'),'utf8'),context);
    vm.runInContext(fs.readFileSync(path.join(assets,'elderfield-panel.js'),'utf8'),context);
    return {elements,body,sent,update:d=>context.GameCompanionPanel.update(d)};
}
const PNG='data:image/png;base64,QQ==';
const base=(over={})=>({epoch:1,scene:3,mode:'party',interactive:true,canOpen:true,labels:{inventory:'Items'},hpLabel:'HP',mpLabel:'MP',levelLabel:'Lv',
    images:{'face:Actor1:0:1':PNG,'icon:6248':PNG},
    party:[{id:1,name:'Ann',level:4,hp:30,mhp:40,mp:5,mmp:10,portrait:'face:Actor1:0:1',states:[{name:'Coffee',icon:'icon:6248'}],equipment:[{name:'Old Hoe',icon:''}]}],
    hud:{time:'9:00 AM',date:'Mon 1, Rebirth',gold:120,currency:'$',energy:75,energyLabel:'Energy',sky:{hour:9,phase:2,season:0,night:false,weather:'rain'}},...over});
test('panel: card, clock, weather and a sky that follows the time of day, season and weather',()=>{
    const p=panel();p.update(base());
    const card=p.elements.party.children[0];
    assert.match(card.textContent,/Ann/);assert.match(card.textContent,/Lv 4/);assert.match(card.textContent,/HP 30\/40/);
    assert.match(card.textContent,/Energy 75/);assert.match(card.textContent,/Coffee/);assert.match(card.textContent,/Old Hoe/);
    const hpFill=card.all().find(n=>n.tagName==='i');assert.equal(hpFill.style.width,'75.0%');
    assert.equal(p.elements.clock.textContent,'9:00 AM');assert.equal(p.elements.weather.textContent,'Deszcz');
    assert.equal(p.body.style.getPropertyValue('--sky-top'),'#2f6fa8');assert.ok(p.body.classList.contains('rain'));
    p.update(base({hud:{...base().hud,sky:{hour:23,phase:4,season:3,weather:'snow'}}}));
    assert.equal(p.body.style.getPropertyValue('--sky-top'),'#060a1a');
    assert.ok(p.body.classList.contains('snow'));assert.ok(!p.body.classList.contains('rain'));
    assert.equal(p.elements.weather.textContent,'Śnieg');
});
test('panel: gold change floats a delta; inventory button sends the command',()=>{
    const p=panel();p.update(base());p.update(base({hud:{...base().hud,gold:150}}));
    const delta=p.elements.gold.children.find(c=>c.className?.startsWith('delta'));
    assert.equal(delta.textContent,'+30');assert.match(delta.className,/plus/);
    const open=p.elements.actions.children[0];assert.equal(open.textContent,'Items');assert.equal(open.disabled,false);
    open.onclick();assert.deepEqual(p.sent.at(-1),{action:'inventory',epoch:1,scene:3});
});
test('panel: Body loss in the same battle shakes the card; a new battle does not',()=>{
    const p=panel(),hits=[];
    p.update(base({mode:'battle'}));const card=p.elements.party.children[0];
    card.animate=(frames,options)=>{const a={options,cancel(){}};hits.push(a);return a;};
    const hp=v=>base({mode:'battle',party:[{...base().party[0],hp:v}]});
    p.update(hp(20));assert.equal(hits.length,1);
    p.update({...hp(10),scene:4});assert.equal(hits.length,1);
});
test('panel: inventory list, game action label on Use, and the game popup as choices',()=>{
    const p=panel();
    const inv=base({mode:'inventory',categories:[{key:'items',name:'Items',enabled:true}],category:'items',slots:[9,14],
        items:[{key:'item:7',name:'Bitter Herb',description:'Restores HP.',count:3,icon:'',action:'Eat',usable:true}],selected:'item:7',canUse:true});
    p.update(inv);
    assert.equal(p.elements.slots.textContent,'🎒 9/14');
    assert.match(p.elements.items.children[0].textContent,/Bitter Herb×3/);
    assert.equal(p.elements.description.hidden,false);
    const [use,back]=p.elements.actions.children;assert.equal(use.textContent,'Eat');assert.equal(back.textContent,'Wstecz');
    use.onclick();assert.deepEqual(p.sent.at(-1),{action:'use',key:'item:7',epoch:1,scene:3});
    p.update({...inv,canUse:false,popup:{kind:'actions',item:'Bitter Herb',amount:0,choices:[{index:0,name:'Use',enabled:true},{index:1,name:'Drop',enabled:true}]}});
    assert.equal(p.elements.popup.hidden,false);assert.equal(p.elements.popupTitle.textContent,'Bitter Herb');
    p.elements.choices.children[1].onclick();assert.deepEqual(p.sent.at(-1),{action:'choice',index:1,epoch:1,scene:3});
    assert.equal(p.elements.actions.children.length,1,'only Back while a popup is open');
    p.update(base());assert.equal(p.elements.inventory.hidden,true);assert.equal(p.elements.popup.hidden,true);
});
test('panel: waiting hides card and clock',()=>{
    const p=panel();p.update(base());p.update({epoch:1,mode:'waiting',party:[],images:{}});
    assert.equal(p.elements.party.children.length,0);assert.equal(p.elements.hud.hidden,true);assert.equal(p.elements.waiting.hidden,false);
});

test('real game: variables, switches and plugin hooks used by the adapter exist',{skip:!GAME},()=>{
    const read=f=>JSON.parse(fs.readFileSync(path.join(GAME,'data',f),'utf8').replace(/^\uFEFF/,''));
    const system=read('System.json');
    const vars={243:'Weather',123:'Day Phase',127:'Hour',128:'Minute',125:'Month',1475:'Energy',1618:'Display Time',1619:'Display Day/Season'};
    for(const [id,name] of Object.entries(vars)) assert.equal(system.variables[id],name,'V'+id);
    assert.equal(system.switches[22],'Night (9pm - 5am)');assert.equal(system.switches[1121],'Snow Storm');assert.equal(system.switches[1123],'Heavy Snow');
    const plugin=f=>fs.readFileSync(path.join(GAME,'js/plugins',f),'utf8');
    assert.match(plugin('DM_LimitedInventory.js'),/_itemActionWindow/);assert.match(plugin('DM_ItemActions.js'),/_customItemActionWindow/);
    assert.match(plugin('DM_LimitedInventory.js'),/_confirmationCommands/);assert.match(plugin('DM_LimitedInventory.js'),/getCurrentPartyInventoryMaxWeight/);
    assert.match(plugin('WTE_UniversalItemScrollFix.js'),/isDummyItem/);
    const notes=read('Items.json').filter(Boolean).map(i=>i.note);
    assert.ok(notes.some(n=>/<actions>[\s\S]*\nUse \((Eat|Wear|Check)\)\s*\n[\s\S]*<\/actions>/i.test(n)),'Use (Label) action lines');
    assert.ok(notes.filter(n=>/<actions>\s*Use\s*<\/actions>/i.test(n)).length>500,'most items: a single Use');
    assert.ok(notes.some(n=>/<actions>[\s\S]*commonEvent\[\d+\] \([^)]+\)[\s\S]*<\/actions>/i.test(n)),'commonEvent[n] (Label)');
    assert.ok(notes.some(n=>/<actions>\s*<\/actions>/i.test(n)),'empty actions (not usable)');
});

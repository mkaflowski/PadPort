// Fear & Hunger (MV) dual-screen profile: read-only adapter + lower-screen panel.
//   node --test tests/fear-and-hunger-dual.test.cjs
//   FEAR_AND_HUNGER_GAME="F:\DepotDownloaderMod\Fear & Hunger" node --test tests/fear-and-hunger-dual.test.cjs
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets');
const adapter=fs.readFileSync(path.join(assets,'fear-and-hunger-dual.js'),'utf8');

function game(profile='fear-and-hunger'){
    let control={active:true,epoch:3,commands:[]},state=null,snapshots=0;
    const states={3:{id:3,name:'Arm cut',iconIndex:0},14:{id:14,name:'Leg cut',iconIndex:0},31:{id:31,name:'Headless',iconIndex:0},
        5:{id:5,name:'Bleeding',iconIndex:160},40:{id:40,name:'Hunger LVL 2',iconIndex:148},13:{id:13,name:"Can't do shit",iconIndex:0}};
    const affected=new Set([3,5,40,13]);
    const actor={actorId:()=>1,name:()=>'Mercenary',hp:12,mhp:40,mp:5,mmp:30,isDead:()=>false,faceName:()=>'',faceIndex:()=>0,
        isStateAffected:id=>affected.has(id),states:()=>[...affected].map(id=>states[id]),equips:()=>[{name:'Sword',iconIndex:0},null]};
    class Scene_Map{} class Scene_Battle{} class Scene_Title{}
    const sandbox={console,Scene_Map,Scene_Battle,Scene_Title,__PADPORT_CONFIG__:{dualScreenProfile:profile},
        setInterval(){},clearInterval(){},addEventListener(){},location:{href:'https://g1.padport.local/index.html'},
        document:{styleSheets:[],createElement(){throw new Error('no canvas in test')}},
        $dataSystem:{gameTitle:'Fear & Hunger'},$dataStates:states,$gameMap:{displayName:()=>'Prison'},
        $gameParty:{members:()=>[actor]},TextManager:{hpA:'Body',mpA:'Mind'},
        SceneManager:{_scene:new Scene_Map()},
        PadPortHost:{dualPoll:()=>JSON.stringify(control),dualSnapshot:s=>{state=JSON.parse(s);snapshots++;},log(){}}};
    sandbox.window=sandbox;vm.createContext(sandbox);vm.runInContext(adapter,sandbox);
    return {sandbox,actor,affected,tick:()=>sandbox.FearHungerDual.tick(),get state(){return state},get snapshots(){return snapshots},
        setControl:c=>{control=c}};
}
test('no adapter for other games',()=>{
    assert.equal(game('look-outside').sandbox.FearHungerDual,undefined);
    assert.equal(game('').sandbox.FearHungerDual,undefined);
});
test('party status uses game terms, limb conditions and icon states only',async()=>{
    const t=game();await t.tick();const s=t.state;
    assert.equal(s.mode,'party');assert.equal(s.epoch,3);assert.equal(s.map,'Prison');assert.equal(s.title,'Fear & Hunger');
    assert.equal(s.hpLabel,'Body');assert.equal(s.mpLabel,'Mind');
    const m=s.party[0];assert.equal(m.name,'Mercenary');assert.equal(m.hp,12);assert.equal(m.mmp,30);
    assert.deepEqual(m.conditions,['Arm cut'],'icon-less limb loss is shown');
    assert.deepEqual(m.states.map(x=>x.name),['Bleeding','Hunger LVL 2'],'technical icon-less states are hidden');
    assert.deepEqual(m.equipment.map(x=>x.name),['Sword']);
});
test('unchanged state is not resent, changes are',async()=>{
    const t=game();await t.tick();await t.tick();assert.equal(t.snapshots,1);
    t.actor.hp=3;await t.tick();assert.equal(t.snapshots,2);assert.equal(t.state.party[0].hp,3);
});
test('title, pre-title map and game over hide the party; battle is a status view',async()=>{
    const t=game();
    t.sandbox.SceneManager._scene=new t.sandbox.Scene_Title();await t.tick();assert.equal(t.state.mode,'waiting');assert.equal(t.state.party.length,0);
    function Scene_PretitleMap(){} t.sandbox.SceneManager._scene=new Scene_PretitleMap();await t.tick();assert.equal(t.state.mode,'waiting');
    t.sandbox.SceneManager._scene=new t.sandbox.Scene_Battle();await t.tick();assert.equal(t.state.mode,'battle');assert.equal(t.state.party.length,1);
});
test('inactive display or a new epoch resets the stream; commands are ignored',async()=>{
    const t=game();await t.tick();
    t.setControl({active:false,epoch:4,commands:[{action:'inventory',epoch:4}]});await t.tick();assert.equal(t.snapshots,1);
    t.setControl({active:true,epoch:5,commands:[{action:'use',epoch:5}]});await t.tick();assert.equal(t.snapshots,2);assert.equal(t.state.epoch,5);
});

class Element{
    constructor(tag){this.tag=tag;this.children=[];this._text='';this.hidden=false;this.parentNode=null;this.attributes=new Map();this.className='';}
    set textContent(v){this.replaceChildren();this._text=String(v);}get textContent(){return this._text+this.children.map(c=>c.textContent||'').join('');}
    append(...n){for(const x of n)this.insertBefore(x,null);}
    replaceChildren(...n){for(const o of this.children)o.parentNode=null;this.children=[];this._text='';this.append(...n);}
    insertBefore(n,b){if(n===b)return;n.remove();const i=b?this.children.indexOf(b):this.children.length;this.children.splice(i,0,n);n.parentNode=this;}
    remove(){if(this.parentNode){const s=this.parentNode.children;s.splice(s.indexOf(this),1);this.parentNode=null;}}
    setAttribute(k,v){this.attributes.set(k,String(v));}
}
function panel(){
    const elements={};let ready=0;const images=[];
    const context={console,__PADPORT_CONFIG__:{language:'pl'},
        document:{documentElement:{},createElement:t=>{const e=new Element(t);if(t==='img')images.push(e);return e;},getElementById:id=>elements[id]||(elements[id]=new Element('div'))},
        GameCompanionHost:{ready:()=>ready++,command(){throw new Error('read-only panel sent a command')}}};
    context.window=context;vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(assets,'ui-strings.js'),'utf8'),context);
    vm.runInContext(fs.readFileSync(path.join(assets,'fear-and-hunger-panel.js'),'utf8'),context);
    return {elements,images,ready:()=>ready,update:d=>context.GameCompanionPanel.update(d)};
}
const PNG='data:image/png;base64,iVBORw0KGgo=';
test('panel shows the party, keeps cards and portraits stable, clears them at the title',async()=>{
    const p=panel();assert.equal(p.ready(),1);
    const data={epoch:1,mode:'party',title:'Fear & Hunger',map:'Prison',hpLabel:'Ciało',mpLabel:'Umysł',images:{'face:Actor1:0':PNG,'icon:160':PNG},
        party:[{id:1,name:'Najemnik',hp:12,mhp:40,mp:5,mmp:30,portrait:'face:Actor1:0',conditions:['Ucięta ręka'],states:[{name:'Krwawienie',icon:'icon:160'}],equipment:[{name:'Miecz',icon:''}]}]};
    p.update(data);
    const party=p.elements.party,card=party.children[0];
    assert.match(card.textContent,/Najemnik/);assert.match(card.textContent,/Ciało 12\/40/);assert.match(card.textContent,/Umysł 5\/30/);
    assert.match(card.textContent,/Ucięta ręka/);assert.match(card.textContent,/Krwawienie/);assert.match(card.textContent,/Miecz/);
    assert.equal(p.elements.mapName.textContent,'Prison');
    const portrait=p.images.find(i=>i.src===PNG&&i.onload);portrait.naturalWidth=1;await portrait.onload();
    const portraitsBefore=p.images.filter(i=>i.onload).length;
    p.update({...data,party:[{...data.party[0],hp:2}]});
    assert.equal(party.children[0],card,'same card object for the same actor');
    assert.equal(p.images.filter(i=>i.onload).length,portraitsBefore,'an HP change does not reload the portrait');
    assert.match(card.textContent,/Ciało 2\/40/);
    p.update({epoch:1,mode:'waiting',title:'Fear & Hunger'});
    assert.equal(party.children.length,0);assert.equal(p.elements.waiting.hidden,false);
});
test('adapter numbers scenes so a new battle is distinguishable from the current one',async()=>{
    const t=game();const first=new t.sandbox.Scene_Battle();
    t.sandbox.SceneManager._scene=first;await t.tick();const a=t.state.scene;
    t.actor.hp=5;await t.tick();assert.equal(t.state.scene,a,'same battle keeps its number');
    t.sandbox.SceneManager._scene=new t.sandbox.Scene_Battle();await t.tick();assert.notEqual(t.state.scene,a);
});
test('Body loss in the same battle shakes the card; new battle, healing and the map do not',()=>{
    const p=panel(),hits=[];
    const member=hp=>({id:1,name:'Najemnik',hp,mhp:40,mp:5,mmp:30,conditions:[],states:[],equipment:[]});
    const send=(mode,scene,hp)=>p.update({epoch:1,mode,scene,map:'Prison',hpLabel:'Ciało',mpLabel:'Umysł',images:{},party:[member(hp)]});
    send('battle',1,30);
    const card=p.elements.party.children[0];
    card.animate=(frames,options)=>{const a={frames,options,cancelled:false,cancel(){a.cancelled=true;}};hits.push(a);return a;};
    send('battle',1,22);assert.equal(hits.length,1,'hit');
    assert.equal(hits[0].options.duration,200);assert.match(hits[0].frames[1].transform,/translateX\(-4px\)/);
    send('battle',1,22);send('battle',1,30);assert.equal(hits.length,1,'no change or healing: no shake');
    send('battle',2,10);assert.equal(hits.length,1,'next battle starts without a shake');
    assert.equal(hits[0].cancelled,true,'leftover animation is cancelled when the battle changes');
    send('party',undefined,5);assert.equal(hits.length,1,'map damage does not shake');
});
test('battle keeps the map name, without an extra status caption',()=>{
    const p=panel();p.update({epoch:1,mode:'battle',map:'Prison',party:[],images:{}});
    assert.equal(p.elements.mapName.textContent,'Prison');
});
test('real game data: limb states and terms match the profile',{skip:!process.env.FEAR_AND_HUNGER_GAME},()=>{
    const www=path.join(process.env.FEAR_AND_HUNGER_GAME,'www','data');
    const read=f=>JSON.parse(fs.readFileSync(path.join(www,f),'utf8').replace(/^\uFEFF/,''));
    const states=read('States.json'),system=read('System.json');
    assert.equal(system.gameTitle,'Fear & Hunger');
    assert.deepEqual([3,14,31].map(id=>states[id].name),['Arm cut','Leg cut','Headless']);
    assert.ok([3,14,31].every(id=>states[id].iconIndex===0),'these conditions have no icon in the game');
    assert.equal(system.terms.basic[2],'Body');assert.equal(system.terms.basic[4],'Mind');
});

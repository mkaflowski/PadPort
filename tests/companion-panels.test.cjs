const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets');
class Element{
    constructor(tag){this.tag=tag;this.children=[];this._text='';this.hidden=false;this.parentNode=null;this.attributes=new Map();this.srcWrites=0;}
    set textContent(value){this.replaceChildren();this._text=String(value);}get textContent(){return this._text+this.children.map(c=>c.textContent||'').join('');}
    set src(value){this._src=value;this.srcWrites++;}get src(){return this._src;}
    append(...nodes){for(const node of nodes)this.insertBefore(node,null);}
    prepend(...nodes){for(const node of nodes.reverse())this.insertBefore(node,this.children[0]||null);}
    replaceChildren(...nodes){for(const old of this.children)old.parentNode=null;this.children=[];this._text='';this.append(...nodes);}
    insertBefore(node,before){if(node===before)return;node.remove();const index=before?this.children.indexOf(before):this.children.length;this.children.splice(index,0,node);node.parentNode=this;}
    remove(){if(this.parentNode){const siblings=this.parentNode.children;siblings.splice(siblings.indexOf(this),1);this.parentNode=null;}}
    setAttribute(key,value){this.attributes.set(key,String(value));}removeAttribute(key){this.attributes.delete(key);}
    scrollIntoView(options){this.scrollCount=(this.scrollCount||0)+1;this.lastScrollOptions=options;}
    animate(keyframes,options){const animation={keyframes,options,cancelled:false,cancel(){this.cancelled=true}};(this.animations||(this.animations=[])).push(animation);return animation;}
}
function panel(name,config={},fontLoader=null){
    const elements={},commands=[],images=[];let ready=0;
    const context={console,scrollY:0,scrollTo(){},__PADPORT_CONFIG__:{language:'pl',...config},
        document:{documentElement:{},createElement:tag=>{const element=new Element(tag);if(tag==='img')images.push(element);return element;},getElementById:id=>elements[id]||(elements[id]=new Element('div'))},
        GameCompanionHost:{ready:()=>ready++,command:s=>commands.push(JSON.parse(s))}};
    if(fontLoader){
        const properties=new Map();context.document.fonts=new Set();
        context.document.documentElement.style={setProperty:(key,value)=>properties.set(key,value),removeProperty:key=>properties.delete(key),getPropertyValue:key=>properties.get(key)||''};
        context.FontFace=class{constructor(family,source){this.family=family;this.source=source;}load(){return fontLoader(this);}};
    }
    context.window=context;vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(assets,'ui-strings.js'),'utf8'),context);
    vm.runInContext(fs.readFileSync(path.join(assets,name+'.js'),'utf8'),context);
    return {context,elements,commands,images,ready:()=>ready,update:data=>context.GameCompanionPanel.update(data)};
}
test('To the Moon notebook has working tabs and descriptions, and clears previous playthrough at title',()=>{
    const p=panel('to-the-moon-panel');assert.equal(p.ready(),1);assert.equal(p.elements.tabs.hidden,true);
    const state={mode:'notebook',characters:[{id:1,name:'Eva',description:'Biogram'}],notes:[{id:3,name:'Latarnia',description:'Żółta łódź'}],items:[{id:4,name:'Królik',description:'Z papieru',count:2}]};
    p.update(state);assert.equal(p.elements.tabs.hidden,false);assert.match(p.elements.entries.textContent,/Latarnia/);
    p.elements.entries.children[0].onclick();assert.match(p.elements.detail.textContent,/Żółta łódź/);
    p.elements.tabs.children[2].onclick();p.elements.entries.children[0].onclick();assert.match(p.elements.detail.textContent,/Z papieru/);
    assert.equal(p.commands.length,0,'Browsing never mutates the game');
    p.update({mode:'title'});assert.equal(p.elements.tabs.hidden,true);assert.equal(p.elements.entries.children.length,0);assert.equal(p.elements.detail.children.length,0);
    p.update({...state,items:[]});assert.equal(p.elements.detail.hidden,true);
});
test('Look Outside retains the shared host handshake and game command envelope',()=>{
    const p=panel('look-outside-panel');assert.equal(p.ready(),1);
    p.update({epoch:8,scene:3,mode:'party',party:[],images:{},canOpen:true});
    p.elements.actions.children[0].onclick();assert.deepEqual(p.commands[0],{action:'inventory',epoch:8,scene:3});
    p.update({epoch:8,scene:4,mode:'inventory',party:[],images:{},canUse:true,interactive:true,target:false,categories:[],items:[{key:'item:1',name:'Bandage',description:'Heal',count:1}],selected:'item:1'});
    p.elements.actions.children[0].onclick();assert.deepEqual(p.commands[1],{action:'use',key:'item:1',epoch:8,scene:4});
});
test('Look Outside replaces the text only after its optional logo loads successfully',()=>{
    const url='https://cdn2.steamgriddb.com/logo_thumb/10c6cea7f006e0752cfe7e68cc3c42c6.png';
    const p=panel('look-outside-panel',{logoUrl:url}),logo=p.elements.gameLogo,title=p.elements.gameTitle;
    assert.equal(logo.src,url);assert.equal(logo.hidden,true);assert.equal(title.hidden,false);
    assert.equal(p.ready(),1,'The panel does not wait for the network');
    logo.naturalWidth=480;logo.onload();assert.equal(logo.hidden,false);assert.equal(title.hidden,true);
    logo.onerror();assert.equal(logo.hidden,true);assert.equal(title.hidden,false);
});
test('Look Outside keeps its text for an unavailable or undecodable logo',()=>{
    const p=panel('look-outside-panel'),logo=p.elements.gameLogo,title=p.elements.gameTitle;
    assert.equal(logo.src,undefined);assert.equal(title.hidden,false);
    logo.naturalWidth=0;logo.onload();assert.equal(logo.hidden,true);assert.equal(title.hidden,false);
});
test('Look Outside uses the translated game caption even with an English app UI',()=>{
    const p=panel('look-outside-panel',{language:'en'});
    p.update({mode:'party',party:[],canOpen:true,labels:{inventory:'Ekwipunek'}});
    assert.equal(p.elements.actions.children[0].textContent,'Ekwipunek');
});
test('Look Outside loads the game font once, applies it after loading, and falls back on failure',async()=>{
    const loaded=[];const p=panel('look-outside-panel',{},face=>{loaded.push(face);return face.source.includes('missing')?Promise.reject(new Error('missing')):Promise.resolve(face);});
    const state={mode:'party',party:[],canOpen:true,fonts:['fonts/Look Outside.ttf']};
    p.update(state);p.update(state);await new Promise(setImmediate);
    assert.equal(loaded.length,1);assert.match(loaded[0].source,/game-font\?path=fonts%2FLook%20Outside.ttf/);
    assert.equal(p.context.document.fonts.size,1);assert.match(p.context.document.documentElement.style.getPropertyValue('--game-font'),/PadPortGameFont/);
    p.update({...state,fonts:['fonts/missing.ttf']});await new Promise(setImmediate);
    assert.equal(p.context.document.fonts.size,0);assert.equal(p.context.document.documentElement.style.getPropertyValue('--game-font'),'');
    assert.equal(p.elements.actions.children.length,1,'Font failure does not block the panel');
});
test('a late font load cannot overwrite a newer game font',async()=>{
    let first;const p=panel('look-outside-panel',{},face=>face.source.includes('first')?new Promise(r=>{first=()=>r(face)}):Promise.resolve(face));
    p.update({mode:'waiting',fonts:['fonts/first.ttf']});p.update({mode:'waiting',fonts:['fonts/second.ttf']});await new Promise(setImmediate);
    const current=p.context.document.documentElement.style.getPropertyValue('--game-font');first();await new Promise(setImmediate);
    assert.equal(p.context.document.documentElement.style.getPropertyValue('--game-font'),current);assert.equal(p.context.document.fonts.size,1);
});
test('inventory availability updates the same button and blocks unavailable actions without a delay',()=>{
    const p=panel('look-outside-panel'),state={epoch:3,scene:8,mode:'party',party:[],canOpen:true,labels:{inventory:'Przedmioty'}};
    p.update(state);const button=p.elements.actions.children[0];assert.equal(button.disabled,false);
    p.update({...state,canOpen:false});assert.equal(p.elements.actions.children[0],button);assert.equal(button.disabled,true);
    button.onclick();assert.equal(p.commands.length,0);
    p.update({...state,canOpen:true});assert.equal(p.elements.actions.children[0],button);assert.equal(button.disabled,false);
    button.onclick();assert.equal(p.commands.length,1);assert.equal(p.commands[0].action,'inventory');
});
test('selecting an item scrolls to its description once, not on ordinary state refreshes',()=>{
    const p=panel('look-outside-panel'),state={epoch:1,scene:4,mode:'inventory',party:[],images:{},canUse:true,interactive:true,target:false,
        category:'item',categories:[],items:[{key:'item:1',name:'First',description:'First description',count:1},{key:'item:2',name:'Second',description:'Second description',count:2}],selected:'item:1'};
    p.update(state);assert.equal(p.elements.description.scrollCount,undefined,'Opening inventory leaves the list visible');
    p.elements.items.children[1].onclick();assert.equal(p.elements.description.scrollCount,undefined,'Wait for the new description from the game');
    p.update({...state,selected:'item:2'});assert.equal(p.elements.description.scrollCount,1);assert.match(p.elements.description.textContent,/Second description/);
    p.update({...state,selected:'item:2'});assert.equal(p.elements.description.scrollCount,1);
    p.elements.items.children[1].onclick();assert.equal(p.elements.description.scrollCount,2,'Tapping the current item works without a new snapshot');
    p.update({...state,category:'keyItem'});assert.equal(p.elements.description.scrollCount,2,'Changing category leaves the list visible');
});
const actor=(id=1)=>({id,name:'Actor '+id,hp:30,mhp:40,mp:12,mmp:20,portrait:'face:'+id,states:[],equipment:['Pipe']});
const partyState=(party=[actor()],extra={})=>({epoch:1,scene:2,mode:'party',hpLabel:'HP',mpLabel:'STM',party,
    images:{'face:1':'data:image/png;base64,AAA','face:2':'data:image/png;base64,BBB'},canOpen:true,...extra});
async function loaded(image){image.naturalWidth=64;await image.onload();}
test('actor card and decoded portrait survive stat refreshes and target selection',async()=>{
    const p=panel('look-outside-panel');p.update(partyState());
    const card=p.elements.party.children[0],photo=card.children[0],portrait=p.images[0];await loaded(portrait);
    p.update(partyState([{...actor(),hp:9,equipment:['Knife']}]));
    assert.equal(p.elements.party.children[0],card);assert.equal(photo.children[0],portrait);assert.equal(p.images.length,1);assert.equal(portrait.srcWrites,1);
    assert.match(card.textContent,/HP 9\/40/);assert.match(card.textContent,/Knife/);
    card.onclick();assert.equal(p.commands.length,0);
    p.update(partyState([{...actor(),target:true,selected:true}],{scene:3,mode:'inventory',target:true,categories:[],items:[],selected:'',interactive:true}));
    assert.equal(p.elements.party.children[0],card);assert.equal(photo.children[0],portrait);
    card.onclick();assert.deepEqual(p.commands[0],{action:'actor',id:1,epoch:1,scene:3});
});
test('missing or failed portrait updates preserve the displayed image until the replacement decodes',async()=>{
    const p=panel('look-outside-panel');p.update(partyState());await loaded(p.images[0]);
    const photo=p.elements.party.children[0].children[0],original=photo.children[0];
    p.update(partyState([{...actor(),portrait:''}],{images:{}}));assert.equal(photo.children[0],original);assert.equal(photo.hidden,false);
    const next=partyState([{...actor(),portrait:'face:changed'}],{images:{'face:changed':'data:image/png;base64,CCC'}});
    p.update(next);const failed=p.images[1];failed.onerror();assert.equal(photo.children[0],original);
    p.update(next);const replacement=p.images[2];let decode;
    replacement.decode=()=>new Promise(resolve=>{decode=resolve});replacement.naturalWidth=64;const waiting=replacement.onload();
    assert.equal(photo.children[0],original);decode();await waiting;assert.equal(photo.children[0],replacement);
});
test('late image callbacks cannot replace a newer portrait or revive a removed actor',async()=>{
    const p=panel('look-outside-panel');p.update(partyState());const first=p.images[0];
    p.update(partyState([{...actor(),portrait:'face:2'}]));const second=p.images[1];await loaded(second);await loaded(first);
    assert.equal(p.elements.party.children[0].children[0].children[0],second);
    p.update(partyState([{...actor(),portrait:'face:3'}],{images:{'face:3':'data:image/png;base64,DDD'}}));const late=p.images[2];
    p.update({mode:'waiting',party:[],images:{}});await loaded(late);assert.equal(p.elements.party.children.length,0);
});
test('reordering the party moves existing cards and portraits instead of recreating them',async()=>{
    const p=panel('look-outside-panel');p.update(partyState([actor(1),actor(2)]));await Promise.all(p.images.map(loaded));
    const [first,second]=p.elements.party.children,firstPortrait=first.children[0].children[0],secondPortrait=second.children[0].children[0];
    p.update(partyState([actor(2),actor(1)]));
    assert.equal(p.elements.party.children[0],second);assert.equal(p.elements.party.children[1],first);assert.equal(p.images.length,2);
    assert.equal(first.children[0].children[0],firstPortrait);assert.equal(second.children[0].children[0],secondPortrait);
    p.update(partyState([actor(2)]));assert.equal(p.elements.party.children.length,1);assert.equal(p.elements.party.children[0],second);
});
test('battle HP loss shakes only the damaged card without replacing its portrait',async()=>{
    const p=panel('look-outside-panel');p.update(partyState([actor(1),actor(2)],{mode:'battle'}));await Promise.all(p.images.map(loaded));
    const [first,second]=p.elements.party.children,portrait=first.children[0].children[0];
    p.update(partyState([{...actor(1),hp:20},actor(2)],{mode:'battle'}));
    assert.equal(first.animations.length,1);assert.equal(first.animations[0].options.duration,200);assert.equal(second.animations,undefined);
    assert.equal(p.elements.party.children[0],first);assert.equal(first.children[0].children[0],portrait);
    p.update(partyState([{...actor(1),hp:20},actor(2)],{mode:'battle'}));assert.equal(first.animations.length,1);
    p.update(partyState([{...actor(1),hp:10},actor(2)],{mode:'battle'}));assert.equal(first.animations.length,2);assert.equal(first.animations[0].cancelled,true);
    p.update({mode:'waiting',party:[]});assert.equal(first.animations[1].cancelled,true);
});
test('healing, entering another scene and reduced-motion settings do not shake cards',()=>{
    const p=panel('look-outside-panel');p.update(partyState());const card=p.elements.party.children[0];
    p.update(partyState([{...actor(),hp:20}]));assert.equal(card.animations,undefined);
    p.update(partyState([{...actor(),hp:10}],{mode:'battle'}));assert.equal(card.animations,undefined);
    p.update(partyState([{...actor(),hp:20}],{mode:'battle'}));assert.equal(card.animations,undefined);
    p.update(partyState([{...actor(),hp:10}],{mode:'battle',scene:3}));assert.equal(card.animations,undefined);
    p.context.matchMedia=()=>({matches:true});p.update(partyState([{...actor(),hp:5}],{mode:'battle',scene:3}));assert.equal(card.animations,undefined);
});
test('equipped items show their icon before the name and keep it during HP refreshes',()=>{
    const p=panel('look-outside-panel');
    const equipped={...actor(),equipment:[{name:'Rura',icon:'icon:42'},{name:'Kurtka',icon:''}]};
    const data=partyState([equipped],{images:{'icon:42':'data:image/png;base64,GEAR'}});p.update(data);
    const card=p.elements.party.children[0],gear=card.children.at(-1),entry=gear.children[0],icon=entry.children[0];
    assert.equal(icon.tag,'img');assert.equal(entry.children[1].textContent,'Rura');assert.match(gear.textContent,/Rura · Kurtka/);
    assert.equal(gear.children[2].children[0].textContent,'Kurtka','Missing icon leaves the name');
    p.update({...data,party:[{...equipped,hp:12}]});assert.equal(card.children.at(-1),gear);assert.equal(gear.children[0].children[0],icon);
    p.update({...data,party:[{...equipped,equipment:[{name:'Nóż',icon:''}]}]});assert.equal(gear.textContent,'Nóż');
});

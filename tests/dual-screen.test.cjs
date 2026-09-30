const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const script=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/look-outside-dual.js'),'utf8');

function setup(profile='look-outside'){
    let control={active:true,epoch:1,commands:[]},state=null,paused=false,locked=false,changing=false,queued=null,moving=false,talking=false,map=1;
    const calls=[],items=[{id:1,name:'Bandage',description:'Heals wounds',kind:'item',itypeId:1,iconIndex:0},{id:2,name:'Story item',kind:'item',itypeId:2,iconIndex:0}];
    const actors=[{actorId:()=>1,name:()=> 'Sam',hp:40,mhp:100,mp:30,mmp:50,equips:()=>[{name:'Pipe'}],states:()=>[],faceName:()=>''}];
    class Scene_Map {isActive(){return true}isMenuEnabled(){return !locked}isBusy(){return false}isMenuCalled(){return false}updateCallMenu(){calls.push('normal-menu-update')}}
    class Scene_Item {isActive(){return true}isActorWindowActive(){return this._actorWindow.active}}
    class Scene_Battle {isActive(){return true}}
    const sandbox={console,Scene_Map,Scene_Item,Scene_Battle,__PADPORT_CONFIG__:{dualScreenProfile:profile},
        setInterval(){},clearInterval(){},addEventListener(){},
        $dataSystem:{},$gameMap:{isEventRunning:()=>locked,mapId:()=>map},$gameMessage:{isBusy:()=>talking},$gamePlayer:{isMoving:()=>moving},
        $gameTemp:{clearDestination:()=>calls.push('clearDestination')},
        $gameParty:{members:()=>actors,numItems:()=>2},TextManager:{hpA:'HP',mpA:'STM'},
        DataManager:{isItem:i=>i.kind==='item',isWeapon:()=>false},SoundManager:{playOk(){}},
        SceneManager:{_scene:new Scene_Map(),_nextScene:null,isSceneChanging:()=>changing,push:scene=>{calls.push(scene);changing=true;queued=scene;sandbox.SceneManager._nextScene=Object.create(scene.prototype);}},
        PadPortHost:{snapshot:()=>JSON.stringify({paused}),dualPoll:()=>JSON.stringify(control),dualSnapshot:s=>{state=JSON.parse(s)},log:m=>calls.push(m)}};
    sandbox.window=sandbox;vm.createContext(sandbox);vm.runInContext(script,sandbox);
    function inventory(s=Object.create(sandbox.Scene_Item.prototype)){
        s._categoryWindow={_list:[{symbol:'item',name:'Items',enabled:true}],active:false,currentSymbol:()=> 'item',
            activate(){this.active=true},deactivate(){this.active=false},select(){},update(){},processOk(){this.active=false;s._itemWindow.active=true},processCancel:()=>calls.push('back')};
        s._itemWindow={_data:items,active:true,selected:0,item(){return this._data[this.selected]},isEnabled:i=>i.id===1,
            isCurrentItemEnabled(){return this.isEnabled(this.item())},select(i){this.selected=i},activate(){this.active=true},deactivate(){this.active=false},
            processOk(){calls.push('item-ok');this.active=false;s._actorWindow.active=true},processCancel:()=>calls.push('back'),processTouch(){}};
        s._actorWindow={active:false,cursorAll:()=>false,cursorFixed:()=>false,index:()=>0,select:i=>calls.push(['actor',i]),
            processOk:()=>calls.push('actor-ok'),processCancel(){this.active=false;s._itemWindow.active=true}};
        s._windowLayer={visible:true,children:[s._itemWindow]};s._cancelButton={visible:true};
        sandbox.SceneManager._scene=s;sandbox.SceneManager._nextScene=null;changing=false;return s;
    }
    return {sandbox,calls,items,actors,inventory,get state(){return state},setControl:c=>{control=c},lock:()=>{locked=true},pause:()=>{paused=true},
        move:value=>{moving=value},talk:value=>{talking=value},changeMap:value=>{map=value},
        async openInventory(){await this.tick();await this.command('inventory');assert.ok(sandbox.SceneManager._nextScene);const scene=inventory(sandbox.SceneManager._nextScene);calls.length=0;return scene;},
        tick:()=>sandbox.LookOutsideDual.tick(),async command(action,args={}){control.commands=[{action,epoch:state.epoch,scene:state.scene,...args}];await sandbox.LookOutsideDual.tick();control.commands=[];}};
}
test('no adapter is installed for unsupported games',()=>assert.equal(setup('').sandbox.LookOutsideDual,undefined));
test('live party state and inventory open use the current game, not a second session',async()=>{
    const t=setup();await t.tick();assert.equal(t.state.party[0].hp,40);assert.equal(t.state.canOpen,true);
    t.actors[0].hp=10;await t.tick();assert.equal(t.state.party[0].hp,10);
    await t.command('inventory');assert.ok(t.calls.includes(t.sandbox.Scene_Item));assert.ok(t.calls.includes('clearDestination'));
});
test('menu locks and battles cannot be bypassed from the other screen',async()=>{
    const t=setup();t.lock();await t.tick();assert.equal(t.state.canOpen,false);await t.command('inventory');assert.equal(t.calls.length,0);
    const battle=setup();battle.sandbox.SceneManager._scene=new battle.sandbox.Scene_Battle();await battle.tick();
    assert.equal(battle.state.mode,'battle');assert.equal(battle.state.canOpen,false);await battle.command('inventory');assert.equal(battle.calls.length,0);
    const dialog=setup();dialog.talk(true);await dialog.tick();assert.equal(dialog.state.canOpen,false);await dialog.command('inventory');assert.equal(dialog.calls.length,0);
});
test('sprinting keeps inventory available and a click opens it once at the end of the step',async()=>{
    const t=setup(),map=t.sandbox.SceneManager._scene,original=map.updateCallMenu;
    t.move(true);await t.tick();assert.equal(t.state.canOpen,true);await t.command('inventory');
    assert.equal(t.sandbox.SceneManager._nextScene,null);assert.notEqual(map.updateCallMenu,original);
    map.updateCallMenu();assert.equal(t.sandbox.SceneManager._nextScene,null);
    await t.command('inventory'); // repeated touches do not stack requests
    t.move(false);map.updateCallMenu();assert.equal(map.updateCallMenu,original);
    assert.equal(t.calls.filter(call=>call===t.sandbox.Scene_Item).length,1);
    const inventory=t.inventory(t.sandbox.SceneManager._nextScene);await t.tick();
    assert.equal(t.state.mode,'inventory');assert.equal(inventory._windowLayer.visible,false);
});
test('queued inventory is cancelled by dialogue, menu locks, transfer, pause or display removal',async()=>{
    for(const reason of ['dialog','locked','transfer','pause','display']){
        const t=setup(),map=t.sandbox.SceneManager._scene,original=map.updateCallMenu;t.move(true);await t.tick();await t.command('inventory');
        if(reason==='dialog')t.talk(true);if(reason==='locked')t.lock();if(reason==='transfer')t.changeMap(2);
        if(reason==='pause')t.pause();if(reason==='display')t.setControl({active:false,epoch:2,commands:[]});
        await t.tick();t.move(false);map.updateCallMenu();
        assert.equal(map.updateCallMenu,original,reason);assert.equal(t.sandbox.SceneManager._nextScene,null,reason);
    }
});
test('an ordinary menu request takes priority over a queued lower-screen inventory',async()=>{
    const t=setup(),map=t.sandbox.SceneManager._scene,original=map.updateCallMenu;t.move(true);await t.tick();await t.command('inventory');
    map.isMenuCalled=()=>true;t.move(false);map.updateCallMenu();
    assert.equal(map.updateCallMenu,original);assert.equal(t.sandbox.SceneManager._nextScene,null);assert.ok(t.calls.includes('normal-menu-update'));
});
test('item and target commands delegate to original windows and reject unusable items',async()=>{
    const t=setup();const scene=await t.openInventory();await t.tick();assert.equal(scene._windowLayer.visible,false);
    await t.command('use',{key:'item:2'});assert.equal(t.calls.length,0);
    await t.command('select',{key:'item:2'});await t.command('use',{key:'item:2'});assert.equal(t.calls.length,0);
    await t.command('select',{key:'item:1'});await t.command('use',{key:'item:1'});assert.ok(t.calls.includes('item-ok'));assert.equal(t.state.target,true);
    await t.command('actor',{id:999});assert.ok(!t.calls.includes('actor-ok'));
    await t.command('actor',{id:1});assert.ok(t.calls.includes('actor-ok'));
});
test('disconnect restores the original inventory, cancel button and touch handlers',async()=>{
    const t=setup(),scene=await t.openInventory(),touch=scene._itemWindow.processTouch;await t.tick();
    assert.notEqual(scene._itemWindow.processTouch,touch);assert.equal(scene._cancelButton.visible,false);
    t.setControl({active:false,epoch:2,commands:[]});await t.tick();
    assert.equal(scene._windowLayer.visible,true);assert.equal(scene._cancelButton.visible,true);assert.equal(scene._itemWindow.processTouch,touch);
});
test('old scene clicks and paused input are rejected',async()=>{
    const t=setup();await t.tick();const old=t.state.scene;await t.openInventory();await t.tick();
    await t.command('use',{key:'item:1',scene:old});assert.equal(t.calls.length,0);
    t.pause();await t.command('use',{key:'item:1'});assert.equal(t.calls.length,0);
});
test('errors restore the main-screen UI instead of stranding the game',async()=>{
    const t=setup(),scene=await t.openInventory();await t.tick();
    t.actors[0].states=()=>{throw new Error('changed plugin')};await t.tick();assert.equal(scene._windowLayer.visible,true);
});
test('an in-flight transport response cannot re-enable a panel after pause',async()=>{
    const t=setup(),scene=await t.openInventory();await t.tick();let resolve;
    t.sandbox.PadPortHost.dualPoll=()=>new Promise(r=>{resolve=r});const pending=t.tick();
    t.sandbox.LookOutsideDual.disable();resolve(JSON.stringify({active:true,epoch:1,commands:[]}));await pending;
    assert.equal(scene._windowLayer.visible,true);
});
test('inventory opened by the game stays on the main screen, including after a lower-screen visit',async()=>{
    const t=setup(),lower=await t.openInventory();await t.tick();assert.equal(lower._windowLayer.visible,false);
    const upper=t.inventory(),touch=upper._itemWindow.processTouch;await t.tick();
    assert.equal(upper._windowLayer.visible,true);assert.equal(upper._cancelButton.visible,true);
    assert.equal(upper._itemWindow.processTouch,touch);assert.equal(lower._windowLayer.visible,true);
    assert.equal(t.state.mode,'party');assert.equal(t.state.items.length,0);assert.equal(t.state.canOpen,false);
    await t.command('use',{key:'item:1'});await t.command('select',{key:'item:2'});
    assert.equal(upper._itemWindow.selected,0);assert.equal(t.calls.length,0);
});
test('inventory caption comes from the live game rather than the app language',async()=>{
    const t=setup();t.sandbox.TextManager.item='Ekwipunek';await t.tick();assert.equal(t.state.labels.inventory,'Ekwipunek');
    t.sandbox.TextManager.item='Items';await t.tick();assert.equal(t.state.labels.inventory,'Items');
});
test('game font URLs become source-relative paths in both WebView and GeckoView',async()=>{
    for(const base of ['https://gtest.padport.local/index.html','http://127.0.0.1:21000/tsecret/index.html']){
        const t=setup();Object.assign(t.sandbox,{URL,location:{href:base},document:{baseURI:base},
            $gameSystem:{mainFontFace:()=> 'rmmz-mainfont, Arial'},FontManager:{_urls:{'rmmz-mainfont':'fonts/Look%20Outside.ttf'}}});
        await t.tick();assert.deepEqual(t.state.fonts,['fonts/Look Outside.ttf']);
        t.sandbox.FontManager._urls['rmmz-mainfont']='https://example.org/foreign.ttf';await t.tick();assert.deepEqual(t.state.fonts,[]);
    }
});
test('a cached portrait survives ImageManager reloads without sending an empty picture',async()=>{
    const t=setup();let ready=true,draws=0;
    t.actors[0].faceName=()=> 'Sam';t.actors[0].faceIndex=()=>0;
    Object.assign(t.sandbox,{ImageManager:{faceWidth:144,faceHeight:144,loadFace:()=>({isReady:()=>ready,canvas:{}})},
        document:{createElement:()=>({getContext:()=>({drawImage:()=>draws++}),toDataURL:()=> 'data:image/png;base64,AAA'})}});
    await t.tick();const key=t.state.party[0].portrait;assert.ok(key);assert.equal(draws,1);
    ready=false;t.actors[0].hp=20;await t.tick();
    assert.equal(t.state.party[0].portrait,key);assert.equal(t.state.images[key],'data:image/png;base64,AAA');assert.equal(draws,1);
});
test('equipment uses the equipped item icon from the game atlas, skipping empty slots',async()=>{
    const t=setup();let crop;
    t.actors[0].equips=()=>[{name:'Rura',iconIndex:42},null,{name:'Kurtka',iconIndex:0}];
    Object.assign(t.sandbox,{ImageManager:{iconWidth:32,iconHeight:32,loadSystem:name=>{assert.equal(name,'IconSet');return {isReady:()=>true,canvas:{}};}},
        document:{createElement:()=>({getContext:()=>({drawImage:(...args)=>{crop=args.slice(1,5);}}),toDataURL:()=> 'data:image/png;base64,GEAR'})}});
    await t.tick();assert.deepEqual(t.state.party[0].equipment,[{name:'Rura',icon:'icon:42'},{name:'Kurtka',icon:''}]);
    assert.equal(t.state.images['icon:42'],'data:image/png;base64,GEAR');assert.deepEqual(crop,[320,64,32,32]);
});

// Optional integration against the installed game's actual menu methods, including its plugin override.
test('Look Outside original item handlers retain consumption, target validation and common events',
    {skip:!process.env.LOOK_OUTSIDE_GAME},async()=>{
    const t=setup(),s=t.sandbox,game=process.env.LOOK_OUTSIDE_GAME;
    s.Window=function(){};s.Stage=function(){};s.Graphics={boxWidth:816};
    vm.runInContext(fs.readFileSync(path.join(game,'js/rmmz_scenes.js'),'utf8'),s);
    vm.runInContext(fs.readFileSync(path.join(game,'js/rmmz_windows.js'),'utf8'),s);
    const core=fs.readFileSync(path.join(game,'js/rmmz_core.js'),'utf8');
    vm.runInContext('function WindowLayer(){};\n'+core.match(/WindowLayer\.prototype\.render = function render\(renderer\) \{[\s\S]*?\n\};/)[0],s);
    const plugin=fs.readFileSync(path.join(game,'js/plugins/bunchastuff.js'),'utf8');
    vm.runInContext(plugin.match(/Scene_Item\.prototype\.onItemOk = function\(\) \{[\s\S]*?\n\};/)[0],s);
    const map=Object.create(s.Scene_Map.prototype);map.isActive=()=>true;map.isMenuEnabled=()=>true;map.isBusy=()=>false;map.isMenuCalled=()=>false;s.SceneManager._scene=map;
    const originalMenu=map.updateCallMenu;t.move(true);await t.tick();await t.command('inventory');map.updateCallMenu();
    assert.equal(s.SceneManager._nextScene,null);t.move(false);map.updateCallMenu();assert.equal(map.updateCallMenu,originalMenu);
    const scene=t.inventory(s.SceneManager._nextScene); // original MZ menu update and original item scene
    scene.isActive=()=>true;scene.isCursorLeft=()=>true;scene.checkGameover=()=>{};
    let count=2,applied=0,events=0,valid=true;
    const actor=t.actors[0];actor.pha=1;actor.canUse=()=>count>0;actor.useItem=()=>count--;actor.index=()=>0;
    s.$gameParty.movableMembers=()=>t.actors;s.$gameParty.setLastItem=()=>{};s.$gameParty.setTargetActor=()=>{};
    s.$gameParty.members=()=>t.actors;s.$gameParty.numItems=()=>count;s.BattleManager={};
    s.$gameTemp.isCommonEventReserved=()=>false;s.SoundManager.playUseItem=()=>{};s.SoundManager.playBuzzer=()=>{};
    s.Game_Action=class{setItemObject(){}isForFriend(){return true}isForAll(){return false}testApply(){return valid}numRepeats(){return 1}apply(){applied++}applyGlobal(){events++}};
    scene._actorWindow.show=()=>{};scene._actorWindow.activate=function(){this.active=true};scene._actorWindow.selectForItem=()=>{};scene._actorWindow.refresh=()=>{};
    scene._itemWindow.redrawCurrentItem=()=>{};
    scene._itemWindow.processOk=function(){this.active=false;scene.onItemOk()};
    scene._actorWindow.processOk=function(){scene.onActorOk()};
    await t.tick();
    assert.doesNotThrow(()=>s.WindowLayer.prototype.render.call(scene._windowLayer,{}),'MZ must skip the upper inventory without accessing the renderer');
    await t.command('use',{key:'item:1'});assert.equal(s.BattleManager._lastSubject,actor);
    valid=false;await t.command('actor',{id:1});assert.equal(count,2);
    valid=true;await t.command('actor',{id:1});assert.equal(count,1,JSON.stringify(t.calls));assert.equal(applied,1);assert.equal(events,1);
});

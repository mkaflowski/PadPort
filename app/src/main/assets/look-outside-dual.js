/* Optional Look Outside companion. The original scene remains the sole owner of inventory actions. */
(() => {
    'use strict';
    if (window.LookOutsideDual || window.__PADPORT_CONFIG__?.dualScreenProfile !== 'look-outside') return;
    const host = window.PadPortHost;
    if (!host?.dualPoll || !host?.dualSnapshot) return;
    const sceneIds = new WeakMap(), pictures = new Map(), lowerInventories = new WeakSet();
    let nextScene = 0, active = false, epoch = -1, busy = false, previous = '', hidden = null, failures = 0, generation = 0;
    let pendingInventory = null;
    const instance = (scene, name) => typeof window[name] === 'function' && scene instanceof window[name];
    const lowerInventory = scene => instance(scene, 'Scene_Item') && lowerInventories.has(scene);
    const sceneId = scene => { if (!sceneIds.has(scene)) sceneIds.set(scene, ++nextScene); return sceneIds.get(scene); };
    const key = item => !item ? '' : (DataManager.isItem(item) ? 'item:' : DataManager.isWeapon(item) ? 'weapon:' : 'armor:') + item.id;
    const paused = () => { try { return !!JSON.parse(host.snapshot()).paused; } catch (_) { return true; } };
    function text(value) {
        let result = String(value || '');
        try { result = Window_Base.prototype.convertEscapeCharacters(result); } catch (_) {}
        return result.replace(/\x1b(?:[a-z]+(?:\[[^\]]*\])?|[{}.!|><^$])/gi, '').replace(/\\[CI]\[\d+\]/gi, '');
    }
    function gameFonts(scene) {
        const sources = [];
        try {
            const family = scene?._itemWindow?.contents?.fontFace || window.$gameSystem?.mainFontFace?.() || 'rmmz-mainfont';
            const root = new URL('.', window.location.href);
            for (const part of family.split(',')) {
                const name = part.trim().replace(/^['"]|['"]$/g, '');
                const registered = window.FontManager?._urls?.[name];
                if (!registered) continue;
                const url = new URL(registered, document.baseURI || root.href);
                if (url.origin !== root.origin || !url.pathname.startsWith(root.pathname)) continue;
                const file = decodeURIComponent(url.pathname.slice(root.pathname.length));
                if (/^fonts\/.*\.(ttf|otf|woff2?)$/i.test(file) && !sources.includes(file)) sources.push(file);
            }
        } catch (_) {} // Keep the readable system font if this build has no transferable font.
        return sources;
    }
    function restore() {
        if (!hidden) return;
        hidden.layer.visible = hidden.visible;
        for (const [win, own, fn] of hidden.touch) { if (own) win.processTouch = fn; else delete win.processTouch; }
        if (hidden.cancel) hidden.cancel.visible = hidden.cancelVisible;
        hidden = null;
    }
    function disable() { generation++; active = false; previous = ''; cancelPendingInventory(); restore(); }
    function redirect(scene) {
        if (hidden?.scene !== scene) restore();
        if (!active || !lowerInventory(scene) || !scene._windowLayer || !scene._itemWindow || !scene._actorWindow) return;
        if (!hidden) {
            hidden = {scene, layer: scene._windowLayer, visible: scene._windowLayer.visible, touch: [],
                cancel: scene._cancelButton, cancelVisible: scene._cancelButton?.visible};
            for (const win of scene._windowLayer.children || []) if (typeof win.processTouch === 'function') {
                hidden.touch.push([win, Object.prototype.hasOwnProperty.call(win, 'processTouch'), win.processTouch]);
                win.processTouch = () => {}; // Hidden upper-screen windows must not react to map taps.
            }
        }
        // MZ WindowLayer overrides render() and checks visible, not PIXI's renderable.
        // The child windows keep their own visible/active flags for gamepad navigation.
        hidden.layer.visible = false;
        if (hidden.cancel) hidden.cancel.visible = false;
    }
    function canOpen(scene) {
        return instance(scene, 'Scene_Map') && scene.isActive() && scene.isMenuEnabled() && !scene.isBusy()
            && !$gameMap.isEventRunning() && !$gameMessage.isBusy();
    }
    function openInventory(scene) {
        SoundManager.playOk(); $gameTemp.clearDestination(); SceneManager.push(Scene_Item);
        if (instance(SceneManager._nextScene, 'Scene_Item')) lowerInventories.add(SceneManager._nextScene);
        scene._mapNameWindow?.hide();
        scene._waitCount = 2;
    }
    function cancelPendingInventory() {
        const request = pendingInventory; pendingInventory = null;
        if (request && request.scene.updateCallMenu === request.hook) {
            if (request.own) request.scene.updateCallMenu = request.original;
            else delete request.scene.updateCallMenu;
        }
    }
    function pendingInventoryValid(request) {
        return pendingInventory === request && active && request.epoch === epoch && SceneManager._scene === request.scene &&
            request.scene.updateCallMenu === request.hook && request.map === $gameMap.mapId?.() &&
            !SceneManager.isSceneChanging() && !paused() && canOpen(request.scene);
    }
    function requestInventory(scene) {
        if (pendingInventory || scene.menuCalling) return;
        if (!$gamePlayer.isMoving()) { openInventory(scene); return; }
        if (typeof scene.updateCallMenu !== 'function') return;
        const request = {scene, epoch, map: $gameMap.mapId?.(), original: scene.updateCallMenu,
            own: Object.prototype.hasOwnProperty.call(scene, 'updateCallMenu')};
        // Check at the game's per-frame menu point, immediately after movement/events,
        // rather than hoping the 250 ms companion poll catches a gap between steps.
        request.hook = function(...args) {
            try {
                if (!pendingInventoryValid(request) || this.menuCalling || this.isMenuCalled?.()) {
                    cancelPendingInventory();
                } else if (!$gamePlayer.isMoving()) {
                    cancelPendingInventory(); openInventory(this); return;
                }
            } catch (error) {
                cancelPendingInventory();
                if (host.log) host.log('Companion inventory: ' + error.message);
            }
            return request.original.apply(this, args);
        };
        pendingInventory = request; scene.updateCallMenu = request.hook;
    }
    function picture(name, bitmap, x, y, width, height, size, images) {
        if (!pictures.has(name)) {
            if (!bitmap?.isReady()) return '';
            try {
                const canvas = document.createElement('canvas'); canvas.width = canvas.height = size;
                const ctx = canvas.getContext('2d'); ctx.imageSmoothingEnabled = false;
                ctx.drawImage(bitmap.canvas, x, y, width, height, 0, 0, size, size);
                if (pictures.size >= 512) pictures.delete(pictures.keys().next().value);
                pictures.set(name, canvas.toDataURL('image/png'));
            } catch (_) { return ''; }
        }
        // Scene changes can clear ImageManager while our thumbnail is still valid.
        // Keep frequently used portraits warm rather than evicting them with old icons.
        const cached = pictures.get(name);
        pictures.delete(name); pictures.set(name, cached);
        images[name] = cached; return name;
    }
    function icon(index, images) {
        if (!(index > 0) || !window.ImageManager) return '';
        const w = ImageManager.iconWidth, h = ImageManager.iconHeight;
        return picture('icon:' + index, ImageManager.loadSystem('IconSet'), index % 16 * w, Math.floor(index / 16) * h, w, h, 24, images);
    }
    function snapshot(scene) {
        const fonts = gameFonts(scene);
        const labels = {inventory: text(window.TextManager?.item).trim()};
        const ready = window.$gameParty && window.$dataSystem && !instance(scene, 'Scene_Title') && !instance(scene, 'Scene_Boot');
        if (!scene || !ready) return {epoch, mode: 'waiting', party: [], images: {}, fonts, labels};
        const images = {}, inventory = lowerInventory(scene), target = inventory && scene.isActorWindowActive();
        const interactive = scene.isActive() && !SceneManager.isSceneChanging() && !paused() && !$gameMessage.isBusy();
        const party = $gameParty.members().map((actor, index) => {
            let portrait = '';
            if (window.ImageManager && actor.faceName()) {
                const n = actor.faceIndex(), w = ImageManager.faceWidth, h = ImageManager.faceHeight;
                portrait = picture('face:' + actor.faceName() + ':' + n, ImageManager.loadFace(actor.faceName()), n % 4 * w, Math.floor(n / 4) * h, w, h, 64, images);
            }
            return {id: actor.actorId(), name: text(actor.name()), hp: actor.hp, mhp: actor.mhp, mp: actor.mp, mmp: actor.mmp,
                portrait, equipment: actor.equips().filter(Boolean).map(item => ({name: text(item.name), icon: icon(item.iconIndex, images)})),
                states: actor.states().filter(state => state.iconIndex > 0).map(state => ({name: text(state.name), icon: icon(state.iconIndex, images)})),
                selected: !!target && (scene._actorWindow.cursorAll() || scene._actorWindow.index() === index),
                target: !!target && interactive && (!scene._actorWindow.cursorFixed() || scene._actorWindow.index() === index)};
        });
        const data = {epoch, scene: sceneId(scene), mode: inventory ? 'inventory' : instance(scene, 'Scene_Battle') ? 'battle' : 'party',
            party, images, fonts, labels, hpLabel: TextManager.hpA, mpLabel: TextManager.mpA, canOpen: interactive && canOpen(scene),
            target: !!target, interactive, categories: [], items: [], selected: '', canUse: false};
        if (inventory) {
            const win = scene._itemWindow, categories = scene._categoryWindow;
            data.categories = (categories?._list || []).map(c => ({key: c.symbol, name: text(c.name), enabled: c.enabled && !target && interactive}));
            data.category = categories?.currentSymbol();
            data.items = (win._data || []).filter(Boolean).map(item => ({key: key(item), name: text(item.name),
                description: text(item.description), count: $gameParty.numItems(item), icon: icon(item.iconIndex, images),
                enabled: interactive && !target && win.isEnabled(item)}));
            data.selected = key(win.item());
            data.canUse = interactive && !target && win.active && win.isCurrentItemEnabled();
        }
        return data;
    }
    function command(c, scene) {
        if (!active || c.epoch !== epoch || !scene || c.scene !== sceneId(scene) || paused()
            || SceneManager.isSceneChanging() || !scene.isActive() || $gameMessage.isBusy()) return;
        if (c.action === 'inventory') {
            if (canOpen(scene)) requestInventory(scene);
            return;
        }
        if (!lowerInventory(scene)) return;
        const win = scene._itemWindow, cat = scene._categoryWindow, actors = scene._actorWindow;
        if (c.action === 'back') {
            const focus = actors.active ? actors : win.active ? win : cat;
            if (focus?.active) focus.processCancel();
        } else if (c.action === 'actor' && actors.active) {
            const index = $gameParty.members().findIndex(a => a.actorId() === c.id);
            if (index >= 0 && (!actors.cursorFixed() || actors.index() === index)) {
                if (!actors.cursorAll()) actors.select(index);
                actors.processOk(); // Original canUse, consumption, targets, common events and game-over checks.
            }
        } else if (c.action === 'category' && !actors.active) {
            const index = cat?._list.findIndex(entry => entry.symbol === c.key && entry.enabled) ?? -1;
            if (index >= 0) { win.deactivate(); cat.activate(); cat.select(index); cat.update(); cat.processOk(); }
        } else if (c.action === 'select' && !actors.active) {
            const index = win._data.findIndex(item => key(item) === c.key);
            if (index >= 0) { cat.deactivate(); win.activate(); win.select(index); }
        } else if (c.action === 'use' && !actors.active && win.active && key(win.item()) === c.key && win.isCurrentItemEnabled()) {
            win.processOk(); // Includes Look Outside's custom Scene_Item.onItemOk.
        }
    }
    async function tick() {
        if (busy) return; busy = true;
        const started = generation;
        try {
            const control = JSON.parse(await host.dualPoll());
            if (started !== generation) return;
            if (!control.active) { disable(); return; }
            if (epoch !== control.epoch) { cancelPendingInventory(); restore(); previous = ''; epoch = control.epoch; }
            active = true;
            if (pendingInventory && !pendingInventoryValid(pendingInventory)) cancelPendingInventory();
            let scene = window.SceneManager?._scene;
            for (const c of control.commands || []) command(c, scene);
            scene = window.SceneManager?._scene;
            if (!scene || !window.$gameParty) { restore(); }
            else redirect(scene);
            const json = JSON.stringify(snapshot(scene));
            if (json.length > 512 * 1024) throw new Error('Companion snapshot exceeds limit');
            if (json !== previous) { await host.dualSnapshot(json); previous = json; }
            failures = 0;
        } catch (error) {
            disable();
            if (failures++ === 0 && host.log) host.log('Look Outside dual screen: ' + error.message);
        } finally { busy = false; }
    }
    window.LookOutsideDual = Object.freeze({tick, disable});
    const timer = window.setInterval(tick, 250);
    window.addEventListener('pagehide', () => { window.clearInterval(timer); disable(); });
})();

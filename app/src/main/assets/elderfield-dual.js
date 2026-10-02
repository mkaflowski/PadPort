/* Optional Welcome to Elderfield (MZ) companion: character card, clock/weather/gold and the
   game's own item menu mirrored on the second screen. Every action goes through the game's
   windows (processOk/processCancel), so its item actions, drop prompts, target window,
   inventory limits and integrity checks stay in charge. The upper menu stays visible. */
(() => {
    'use strict';
    if (window.ElderfieldDual || window.__PADPORT_CONFIG__?.dualScreenProfile !== 'welcome-to-elderfield') return;
    const host = window.PadPortHost;
    if (!host?.dualPoll || !host?.dualSnapshot) return;
    // Game variables/switches (data/System.json names in comments).
    const V = {weather: 243, dayPhase: 123, hour: 127, minute: 128, season: 125, energy: 1475, time: 1618, date: 1619};
    const S = {night: 22, snowStorm: 1121, rainStorm: 1122, heavySnow: 1123};
    const RAIN = [2, 6, 7, 8], STORM = [4], CLOUDY = [3];   // "Weather" values used by the game's events
    const WAITING = ['Scene_Boot', 'Scene_Title', 'Scene_Gameover', 'Scene_SplashFlipbook'];
    const sceneIds = new WeakMap(), bitmapIds = new WeakMap(), pictures = new Map();
    let nextScene = 0, nextBitmap = 0, active = false, epoch = -1, busy = false, previous = '', failures = 0, generation = 0;
    let inGame = false, pendingInventory = null;
    const instance = (scene, name) => typeof window[name] === 'function' && scene instanceof window[name];
    const named = (scene, name) => instance(scene, name) || scene?.constructor?.name === name;
    const sceneId = scene => { if (!sceneIds.has(scene)) sceneIds.set(scene, ++nextScene); return sceneIds.get(scene); };
    const bitmapId = bitmap => { if (!bitmapIds.has(bitmap)) bitmapIds.set(bitmap, ++nextBitmap); return bitmapIds.get(bitmap); };
    const key = item => !item ? '' : (DataManager.isItem(item) ? 'item:' : DataManager.isWeapon(item) ? 'weapon:' : 'armor:') + item.id;
    const paused = () => { try { return !!JSON.parse(host.snapshot()).paused; } catch (_) { return true; } };
    const variable = id => { try { return $gameVariables.value(id); } catch (_) { return 0; } };
    const flag = id => { try { return !!$gameSwitches.value(id); } catch (_) { return false; } };

    /** The game translates at draw time (Hendrix localization); English passes through. */
    function translate(value) {
        const source = String(value ?? '');
        const fn = window.WTE_Translate || window.translateText;
        if (!source || typeof fn !== 'function') return source;
        try { const result = fn(source); return result == null ? source : String(result); } catch (_) { return source; }
    }
    function text(value) {
        let result = translate(value);
        try { result = Window_Base.prototype.convertEscapeCharacters(result); } catch (_) {}
        return result.replace(/<br\s*\/?>/gi, '\n').replace(/\x1b(?:[a-z]+(?:\[[^\]]*\])?|[{}.!|><^$])/gi, '')
            .replace(/\\[CI]\[\d+\]/gi, '').replace(/[ \t]+\n/g, '\n').trim();
    }
    function gameFonts() {
        const sources = [];
        try {
            const family = window.$gameSystem?.mainFontFace?.() || 'rmmz-mainfont';
            const root = new URL('.', window.location.href);
            for (const part of family.split(',')) {
                const registered = window.FontManager?._urls?.[part.trim().replace(/^['"]|['"]$/g, '')];
                if (!registered) continue;
                const url = new URL(registered, document.baseURI || root.href);
                if (url.origin !== root.origin || !url.pathname.startsWith(root.pathname)) continue;
                const file = decodeURIComponent(url.pathname.slice(root.pathname.length));
                if (/^fonts\/.*\.(ttf|otf|woff2?)$/i.test(file) && !sources.includes(file)) sources.push(file);
            }
        } catch (_) {}
        return sources;
    }
    function picture(name, bitmap, x, y, width, height, size, images) {
        if (!pictures.has(name)) {
            if (!bitmap?.isReady?.()) return '';
            try {
                const canvas = document.createElement('canvas'); canvas.width = canvas.height = size;
                const ctx = canvas.getContext('2d'); ctx.imageSmoothingEnabled = false;
                ctx.drawImage(bitmap.canvas, x, y, width, height, 0, 0, size, size);
                if (pictures.size >= 512) pictures.delete(pictures.keys().next().value);
                pictures.set(name, canvas.toDataURL('image/png'));
            } catch (_) { return ''; }
        }
        const cached = pictures.get(name);
        pictures.delete(name); pictures.set(name, cached);   // keep portraits warm (LRU)
        images[name] = cached; return name;
    }
    function icon(index, images) {
        if (!(index > 0) || !window.ImageManager) return '';
        const w = ImageManager.iconWidth || 32, h = ImageManager.iconHeight || 32;
        return picture('icon:' + index, ImageManager.loadSystem('IconSet'), index % 16 * w, Math.floor(index / 16) * h, w, h, 24, images);
    }
    function portrait(actor, images) {
        if (!window.ImageManager || !actor.faceName()) return '';
        // KC_CompositeBitmaps builds the face from the character creator's layers and returns
        // a new bitmap when they change, so the bitmap identity is part of the key.
        const bitmap = ImageManager.loadFace(actor.faceName()), n = actor.faceIndex();
        const w = ImageManager.faceWidth || 144, h = ImageManager.faceHeight || 144;
        return picture('face:' + actor.faceName() + ':' + n + ':' + bitmapId(bitmap), bitmap, n % 4 * w, Math.floor(n / 4) * h, w, h, 96, images);
    }
    function weather() {
        if (flag(S.snowStorm) || flag(S.heavySnow)) return 'snow';
        const value = variable(V.weather);
        if (flag(S.rainStorm) || STORM.includes(value)) return 'storm';
        if (RAIN.includes(value)) return 'rain';
        if (CLOUDY.includes(value)) return 'cloudy';
        return 'clear';
    }
    /** `<actions>` notetag (DM_ItemActions): -1 none (generic Use/Drop window), 0 empty, n entries. */
    function actions(item) {
        const match = /<actions>([\s\S]*?)<\/actions>/i.exec(item?.note || '');
        if (!match) return {count: -1, label: ''};
        const lines = match[1].split(/\r?\n/).map(line => line.trim()).filter(Boolean);
        if (lines.length !== 1) return {count: lines.length, label: ''};
        const parts = /^([^([]+?)\s*(?:\[\d+\])?\s*(?:\((.*)\))?$/.exec(lines[0]);
        return {count: 1, label: text(parts ? (parts[2] || parts[1]) : lines[0])};
    }
    function itemScene(scene) { return instance(scene, 'Scene_Item') && scene._itemWindow && scene._categoryWindow; }
    /** The game's popup that currently owns input, outermost first. */
    function popup(scene) {
        const windows = [['confirm', scene._confirmationCommands], ['actions', scene._customItemActionWindow],
            ['actions', scene._itemActionWindow], ['target', scene._actorWindow]];
        for (const [kind, win] of windows) if (win && win.active && win.visible !== false && win.openness !== 0) return {kind, win};
        return null;
    }
    function canOpen(scene) {
        return instance(scene, 'Scene_Map') && scene.isActive() && !scene.isBusy?.() && scene.isMenuEnabled?.() !== false
            && !$gameMap.isEventRunning() && !$gameMessage.isBusy() && !window.$gameSystem?._wtePauseLocked;
    }
    function openInventory(scene) {
        // Same as the game's inventory hotkey (common event 313) and DM_LimitedInventory callItemScene.
        SoundManager.playOk(); $gameTemp.clearDestination(); SceneManager.push(Scene_Item);
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
        // Open at the game's own per-frame menu point once the player stops between tiles.
        request.hook = function(...args) {
            try {
                if (!pendingInventoryValid(request) || this.menuCalling || this.isMenuCalled?.()) cancelPendingInventory();
                else if (!$gamePlayer.isMoving()) { cancelPendingInventory(); openInventory(this); return; }
            } catch (error) {
                cancelPendingInventory();
                if (host.log) host.log('Elderfield inventory: ' + error.message);
            }
            return request.original.apply(this, args);
        };
        pendingInventory = request; scene.updateCallMenu = request.hook;
    }
    function hud() {
        let time = variable(V.time), date = variable(V.date);
        const hour = Number(variable(V.hour)) || 0, minute = Number(variable(V.minute)) || 0;
        if (typeof time !== 'string' || !time) time = String(hour).padStart(2, '0') + ':' + String(minute).padStart(2, '0');
        return {time: text(time), date: typeof date === 'string' ? text(date) : '', gold: $gameParty.gold(),
            currency: text(TextManager.currencyUnit), energy: Number(variable(V.energy)) || 0, energyLabel: translate('Energy'),
            sky: {hour, minute, phase: Number(variable(V.dayPhase)) || 0, season: ((Number(variable(V.season)) || 0) % 4 + 4) % 4,
                night: flag(S.night), weather: weather()}};
    }
    function card(actor, images) {
        return {id: actor.actorId(), name: text(actor.name()), level: actor.level, hp: actor.hp, mhp: actor.mhp, mp: actor.mp, mmp: actor.mmp,
            portrait: portrait(actor, images),
            states: actor.states().filter(state => state && state.iconIndex > 0).map(state => ({name: text(state.name), icon: icon(state.iconIndex, images)})),
            equipment: actor.equips().filter(Boolean).map(item => ({name: text(item.name), icon: icon(item.iconIndex, images)}))};
    }
    function inventory(scene, data, images) {
        const iw = scene._itemWindow, cw = scene._categoryWindow, open = popup(scene);
        data.categories = (cw._list || []).map(c => ({key: c.symbol, name: text(c.name), enabled: c.enabled !== false && !open && data.interactive}));
        data.category = cw.currentSymbol?.() || '';
        data.items = (iw._data || []).filter(item => item && !item.isDummyItem).map(item => {
            const info = actions(item);
            return {key: key(item), name: text(item.name), description: text(item.description), count: $gameParty.numItems(item),
                icon: icon(item.iconIndex, images), action: info.label, usable: info.count !== 0};
        });
        const current = iw.index() >= 0 ? iw.item() : null;
        data.selected = current && !current.isDummyItem ? key(current) : '';
        const selected = data.items.find(item => item.key === data.selected);
        data.canUse = !!selected && selected.usable && data.interactive && !open && iw.active;
        try { data.slots = [$gameContainers.getCurrentPartyInventoryWeight(), $gameContainers.getCurrentPartyInventoryMaxWeight()]; } catch (_) {}
        if (open) {
            const list = open.kind === 'target' ? $gameParty.members().map(actor => ({name: text(actor.name()), enabled: true}))
                : (open.win._list || []).map(c => ({name: text(c.name), enabled: c.enabled !== false}));
            data.popup = {kind: open.kind, choices: list.map((c, index) => ({index, name: c.name, enabled: c.enabled && data.interactive})),
                amount: open.kind === 'confirm' ? Number(scene._depositWindow?.amount) || 1 : 0, item: selected?.name || ''};
        }
    }
    function snapshot(scene) {
        // TextManager reads $dataSystem, which is still null while the game boots.
        const fonts = gameFonts(), labels = {inventory: window.$dataSystem ? text(TextManager.item) : ''};
        if (!scene || WAITING.some(name => named(scene, name))) inGame = false;
        // Menus from the title (options, load) carry no party; the item menu is reachable only in play.
        else if ((instance(scene, 'Scene_Map') || instance(scene, 'Scene_Battle') || itemScene(scene)) && $gameParty?.members().length) inGame = true;
        if (!inGame || !window.$gameParty || !$gameParty.members().length) return {epoch, mode: 'waiting', party: [], images: {}, fonts, labels};
        const images = {}, items = itemScene(scene);
        const interactive = scene.isActive() && !SceneManager.isSceneChanging() && !paused() && !$gameMessage.isBusy();
        const data = {epoch, scene: sceneId(scene), mode: items ? 'inventory' : instance(scene, 'Scene_Battle') ? 'battle' : 'party',
            fonts, labels, images, interactive, hpLabel: text(TextManager.hpA), mpLabel: text(TextManager.mpA), levelLabel: text(TextManager.levelA),
            party: $gameParty.members().map(actor => card(actor, images)), hud: hud(), canOpen: interactive && canOpen(scene)};
        if (items) inventory(scene, data, images);
        return data;
    }
    function command(c, scene) {
        if (!active || c.epoch !== epoch || !scene || c.scene !== sceneId(scene) || paused()
            || SceneManager.isSceneChanging() || !scene.isActive() || $gameMessage.isBusy()) return;
        if (c.action === 'inventory') { if (canOpen(scene)) requestInventory(scene); return; }
        if (!itemScene(scene)) return;
        const iw = scene._itemWindow, cw = scene._categoryWindow, open = popup(scene);
        if (c.action === 'back') {
            if (open) open.win.processCancel();
            else if (iw.active) iw.processCancel();        // the game closes the menu (or refuses when over capacity)
            else if (cw.active) cw.processCancel();
            else if (typeof scene.popScene === 'function') scene.popScene();
        } else if (c.action === 'choice' && open) {
            const index = Number(c.index);
            if (open.kind === 'target') {
                if (index >= 0 && index < $gameParty.size() && (!open.win.cursorFixed?.() || open.win.index() === index)) {
                    if (!open.win.cursorAll?.()) open.win.select(index);
                    open.win.processOk();
                }
            } else if (open.win._list?.[index] && open.win._list[index].enabled !== false) {
                open.win.select(index); open.win.processOk();
            }
        } else if (open) {
            return;   // popups own the input until they close (DM_ModalShieldFix, WTE_DiscardDesyncLock)
        } else if (c.action === 'category') {
            const index = (cw._list || []).findIndex(entry => entry.symbol === c.key && entry.enabled !== false);
            if (index >= 0) { iw.deactivate(); cw.activate(); cw.select(index); iw.setCategory(cw.currentSymbol()); }
        } else if (c.action === 'select') {
            const index = (iw._data || []).findIndex(item => item && !item.isDummyItem && key(item) === c.key);
            // The category window must be inactive first, or the next frame deselects the list.
            if (index >= 0) { cw.deactivate(); iw.activate(); iw.select(index); }
        } else if (c.action === 'use' && iw.active && iw.index() >= 0 && key(iw.item()) === c.key) {
            iw.processOk();   // DM_ItemActions: Use/Eat, action window, common event or drop
        }
    }
    function disable() { generation++; active = false; previous = ''; cancelPendingInventory(); }
    async function tick() {
        if (busy) return; busy = true;
        const started = generation;
        try {
            const control = JSON.parse(await host.dualPoll());
            if (started !== generation) return;
            if (!control.active) { disable(); return; }
            if (epoch !== control.epoch) { cancelPendingInventory(); previous = ''; epoch = control.epoch; }
            active = true;
            if (pendingInventory && !pendingInventoryValid(pendingInventory)) cancelPendingInventory();
            for (const c of control.commands || []) command(c, window.SceneManager?._scene);
            const json = JSON.stringify(snapshot(window.SceneManager?._scene));
            if (json.length > 512 * 1024) throw new Error('Companion snapshot exceeds limit');
            if (json !== previous) { await host.dualSnapshot(json); previous = json; }
            failures = 0;
        } catch (error) {
            disable();
            if (failures++ === 0 && host.log) host.log('Elderfield dual screen: ' + error.message);
        } finally { busy = false; }
    }
    window.ElderfieldDual = Object.freeze({tick, disable});
    const timer = window.setInterval(tick, 250);
    window.addEventListener('pagehide', () => { window.clearInterval(timer); disable(); });
})();

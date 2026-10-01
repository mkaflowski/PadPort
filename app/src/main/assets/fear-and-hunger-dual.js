/* Optional Fear & Hunger (MV) companion: read-only party status for the second screen.
   It only reads the running game; menus, items and battle commands stay on the main screen. */
(() => {
    'use strict';
    if (window.FearHungerDual || window.__PADPORT_CONFIG__?.dualScreenProfile !== 'fear-and-hunger') return;
    const host = window.PadPortHost;
    if (!host?.dualPoll || !host?.dualSnapshot) return;
    // Icon-less but important conditions of the Fear & Hunger database (names come from the game).
    const LIMB_STATES = [3, 14, 31]; // Arm cut, Leg cut, Headless
    const WAITING_SCENES = ['Scene_Boot', 'Scene_Title', 'Scene_Gameover', 'Scene_PretitleMap'];
    const pictures = new Map(), sceneIds = new WeakMap();
    let nextScene = 0;
    // Each scene object gets its own number, so the panel can tell one battle from the next.
    const sceneId = scene => { if (!sceneIds.has(scene)) sceneIds.set(scene, ++nextScene); return sceneIds.get(scene); };
    let active = false, epoch = -1, busy = false, previous = '', failures = 0, generation = 0, fontCache = null;
    const instance = (scene, name) => typeof window[name] === 'function' && scene instanceof window[name];

    function text(value) {
        let result = String(value || '');
        try { result = Window_Base.prototype.convertEscapeCharacters(result); } catch (_) {}
        return result.replace(/\x1b(?:[a-z]+(?:\[[^\]]*\])?|[{}.!|><^$])/gi, '').replace(/\\[CI]\[\d+\]/gi, '').trim();
    }
    /** MV registers GameFont in fonts/gamefont.css; MZ (other builds) in FontManager._urls. */
    function gameFonts() {
        if (fontCache) return fontCache;
        const sources = [];
        try {
            const root = new URL('.', window.location.href);
            const add = (raw, base) => {
                const url = new URL(raw, base || root.href);
                if (url.origin !== root.origin || !url.pathname.startsWith(root.pathname)) return;
                const file = decodeURIComponent(url.pathname.slice(root.pathname.length));
                if (/^fonts\/.*\.(ttf|otf|woff2?)$/i.test(file) && !sources.includes(file)) sources.push(file);
            };
            for (const url of Object.values(window.FontManager?._urls || {})) add(url);
            for (const sheet of Array.from(document.styleSheets || [])) {
                let rules = [];
                try { rules = Array.from(sheet.cssRules || []); } catch (_) { continue; }
                for (const rule of rules) {
                    const family = rule.style?.getPropertyValue?.('font-family') || '';
                    if (!/GameFont/i.test(family)) continue;
                    const match = /url\(\s*['"]?([^'")]+)['"]?\s*\)/.exec(rule.style.getPropertyValue('src') || '');
                    if (match) add(match[1], sheet.href || root.href);
                }
            }
        } catch (_) {} // Keep the readable system font.
        if (sources.length) fontCache = sources;
        return sources;
    }
    function picture(name, bitmap, x, y, width, height, size, images) {
        if (!pictures.has(name)) {
            if (!bitmap?.isReady()) return '';
            try {
                const canvas = document.createElement('canvas'); canvas.width = canvas.height = size;
                const ctx = canvas.getContext('2d'); ctx.imageSmoothingEnabled = false;
                ctx.drawImage(bitmap._canvas || bitmap.canvas, x, y, width, height, 0, 0, size, size);
                if (pictures.size >= 512) pictures.delete(pictures.keys().next().value);
                pictures.set(name, canvas.toDataURL('image/png'));
            } catch (_) { return ''; }
        }
        const cached = pictures.get(name);
        pictures.delete(name); pictures.set(name, cached);   // keep frequently used portraits warm
        images[name] = cached; return name;
    }
    function icon(index, images) {
        if (!(index > 0) || !window.ImageManager) return '';
        const w = Window_Base._iconWidth || ImageManager.iconWidth || 32, h = Window_Base._iconHeight || ImageManager.iconHeight || 32;
        return picture('icon:' + index, ImageManager.loadSystem('IconSet'), index % 16 * w, Math.floor(index / 16) * h, w, h, 24, images);
    }
    function portrait(actor, images) {
        if (!window.ImageManager || !actor.faceName()) return '';
        const n = actor.faceIndex(), w = Window_Base._faceWidth || ImageManager.faceWidth || 144,
            h = Window_Base._faceHeight || ImageManager.faceHeight || 144;
        // The face file changes with the body (e.g. Actor1 -> Actor1L), so the key includes it.
        return picture('face:' + actor.faceName() + ':' + n, ImageManager.loadFace(actor.faceName()), n % 4 * w, Math.floor(n / 4) * h, w, h, 96, images);
    }
    function mode(scene) {
        if (!scene || !window.$gameParty || !window.$dataSystem || !window.$gameMap) return 'waiting';
        if (WAITING_SCENES.some(name => instance(scene, name) || scene.constructor?.name === name)) return 'waiting';
        return instance(scene, 'Scene_Battle') ? 'battle' : 'party';
    }
    function snapshot(scene) {
        const fonts = gameFonts(), current = mode(scene);
        const base = {epoch, mode: current, fonts, title: text(window.$dataSystem?.gameTitle) || 'Fear & Hunger'};
        if (current === 'waiting') return {...base, party: [], images: {}};
        const images = {};
        const party = $gameParty.members().map(actor => {
            const conditions = LIMB_STATES.filter(id => window.$dataStates?.[id] && actor.isStateAffected(id))
                .map(id => text($dataStates[id].name)).filter(Boolean);
            const seen = new Set();
            const states = actor.states().filter(state => state && state.iconIndex > 0 && !seen.has(state.id) && seen.add(state.id))
                .map(state => ({name: text(state.name), icon: icon(state.iconIndex, images)}));
            return {id: actor.actorId(), name: text(actor.name()), hp: actor.hp, mhp: actor.mhp, mp: actor.mp, mmp: actor.mmp,
                dead: actor.isDead(), portrait: portrait(actor, images), conditions, states,
                equipment: actor.equips().filter(Boolean).map(item => ({name: text(item.name), icon: icon(item.iconIndex, images)}))};
        });
        let map = '';
        try { map = text($gameMap.displayName()); } catch (_) {}
        return {...base, scene: sceneId(scene), map, party, images, hpLabel: text(TextManager.hpA), mpLabel: text(TextManager.mpA)};
    }
    function disable() { generation++; active = false; previous = ''; }
    async function tick() {
        if (busy) return; busy = true;
        const started = generation;
        try {
            const control = JSON.parse(await host.dualPoll());
            if (started !== generation) return;
            if (!control.active) { disable(); return; }
            if (epoch !== control.epoch) { previous = ''; epoch = control.epoch; }
            active = true;   // read-only profile: commands from the panel are ignored
            const json = JSON.stringify(snapshot(window.SceneManager?._scene));
            if (json.length > 512 * 1024) throw new Error('Companion snapshot exceeds limit');
            if (json !== previous) { await host.dualSnapshot(json); previous = json; }
            failures = 0;
        } catch (error) {
            disable();
            if (failures++ === 0 && host.log) host.log('Fear & Hunger dual screen: ' + error.message);
        } finally { busy = false; }
    }
    window.FearHungerDual = Object.freeze({tick, disable});
    const timer = window.setInterval(tick, 250);
    window.addEventListener('pagehide', () => { window.clearInterval(timer); disable(); });
})();

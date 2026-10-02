/* PadPort: standard Gamepad API, or native keyboard output when explicitly selected. */
(() => {
    'use strict';
    if (window.PadPort) return;
    const config = window.__PADPORT_CONFIG__ || {};
    const text = window.PadPortI18n.text;
    let revision = -1;
    let pads = [null, null, null, null];
    let lastPaused = false;
    let inputMode = 'gamepad';
    let engineStopped = false;
    let viewportGraphics = null;
    let viewportSignature = '';
    const host = window.PadPortHost;

    function log(message) {
        if (host && host.log) host.log(String(message).slice(0, 2000));
    }
    function clearInput() {
        if (window.Input && typeof window.Input.clear === 'function') window.Input.clear();
        if (window.TouchInput && typeof window.TouchInput.clear === 'function') window.TouchInput.clear();
    }
    function viewportSize() {
        return [window.innerWidth || document.documentElement.clientWidth,
                window.innerHeight || document.documentElement.clientHeight];
    }
    function updateFitScale() {
        const [width, height] = viewportSize();
        if (width > 0 && height > 0 && this._width > 0 && this._height > 0) {
            this._stretchEnabled = true;
            this._realScale = Math.min(width / this._width, height / this._height);
        }
    }
    function fitToViewport() {
        const g = window.Graphics;
        if (!g || !g._canvas || !(g._width > 0 && g._height > 0) || typeof g._updateAllElements !== 'function') return false;
        const [width, height] = viewportSize();
        if (!(width > 0 && height > 0)) return false;
        const signature = [width, height, g._width, g._height].join(':');
        const expected = Math.min(width / g._width, height / g._height);
        // Fullscreen WebView has no browser toolbar. Bypass MZ's mobile 0.9
        // height allowance and plugin integer scaling, without resizing the map.
        const changed = viewportGraphics !== g || viewportSignature !== signature ||
            g._updateRealScale !== updateFitScale || !g._stretchEnabled ||
            Math.abs(g._realScale - expected) > 1e-9;
        g._updateRealScale = updateFitScale;
        if (changed) {
            viewportGraphics = g;
            viewportSignature = signature;
            // Use the engine's layout path: canvas, video and touch coordinates
            // must all agree on the same scale, not just a CSS transform.
            g._updateAllElements();
        }
        return true;
    }
    function event(name, pad) {
        const e = new Event(name);
        Object.defineProperty(e, 'gamepad', {value: pad});
        window.dispatchEvent(e);
    }
    function poll() {
        fitToViewport();
        if (!host || !host.snapshot) return pads.slice();
        let data;
        try { data = JSON.parse(host.snapshot()); }
        catch (e) { log(text('snapshot_error', e.message)); return pads.slice(); }
        if (data.revision === revision) return pads.slice();
        revision = data.revision;
        const mode = data.inputMode === 'keyboard' ? 'keyboard' : 'gamepad';
        if (mode !== inputMode) clearInput();
        inputMode = mode;
        const next = [null, null, null, null];
        for (let i = 0; i < next.length; i++) {
            const raw = inputMode === 'keyboard' ? null : (data.pads || [])[i];
            const old = pads[i];
            if (old && (!raw || old._nativeId !== raw.nativeId)) {
                old.connected = false;
                old.buttons.forEach(b => {b.pressed = b.touched = false; b.value = 0;});
                old.axes.fill(0);
                event('gamepaddisconnected', old);
                clearInput(); // MV/MZ otherwise keep the last state of a removed pad.
            }
            if (!raw) continue;
            const same = old && old.connected && old._nativeId === raw.nativeId;
            const pad = same ? old : {
                id: 'PadPort · ' + raw.id, index: i, connected: true, mapping: 'standard',
                axes: [0, 0, 0, 0], buttons: Array.from({length: 17}, () => ({pressed: false, touched: false, value: 0})),
                timestamp: 0, _nativeId: raw.nativeId
            };
            for (let a = 0; a < 4; a++) pad.axes[a] = data.paused ? 0 : Math.max(-1, Math.min(1, Number(raw.axes[a]) || 0));
            for (let b = 0; b < 17; b++) {
                const value = data.paused ? 0 : Math.max(0, Math.min(1, Number(raw.buttons[b]) || 0));
                Object.assign(pad.buttons[b], {value, pressed: value > 0.5, touched: value > 0});
            }
            pad.timestamp = performance.now();
            next[i] = pad;
            if (!same) event('gamepadconnected', pad);
        }
        if (data.paused && !lastPaused) clearInput();
        lastPaused = Boolean(data.paused);
        pads = next;
        return pads.slice();
    }
    Object.defineProperty(navigator, 'getGamepads', {value: poll, configurable: false});
    Object.defineProperty(navigator, 'webkitGetGamepads', {value: poll, configurable: false});

    // Read-only Node.js 'fs' for PC-only plugin code that checks game files, e.g.
    // Look Outside's monsterImageExists() on every battle pose change. Files come
    // from the same PadPort origin as everything else (synchronous XHR, like the
    // blocking fs calls it replaces), matched case-insensitively like NW.js on
    // Windows. Results are cached - game files do not change during a session.
    // Writing is refused: saves stay in browser storage (no process / nw below).
    const fileProbe = new Map();
    function fsError(code, op, file) {
        const e = new Error(code + ': ' + op + " '" + file + "'");
        e.code = code; e.syscall = op; e.path = String(file);
        return e;
    }
    function gameUrl(file) {
        const rel = String(file && file.href || file).replace(/\\/g, '/').split('/')
            .filter(s => s && s !== '.').map(encodeURIComponent).join('/');
        return new URL(rel, document.baseURI).href;
    }
    function probe(file) {
        const url = gameUrl(file);
        if (fileProbe.has(url)) return fileProbe.get(url);
        let found = {exists: false, size: 0};
        try {
            const x = new XMLHttpRequest();
            x.open('HEAD', url, false);
            x.send();
            if (x.status === 200) found = {exists: true, size: parseInt(x.getResponseHeader('Content-Length'), 10) || 0};
        } catch (e) { log('fs ' + file + ': ' + e.message); }
        fileProbe.set(url, found);
        return found;
    }
    function readFileSync(file, options) {
        const encoding = typeof options === 'string' ? options : options && options.encoding;
        const x = new XMLHttpRequest();
        x.open('GET', gameUrl(file), false);
        if (!encoding) x.overrideMimeType('text/plain; charset=x-user-defined');   // raw bytes
        x.send();
        if (x.status !== 200) throw fsError('ENOENT', 'open', file);
        if (encoding) return x.responseText;
        const s = x.responseText, bytes = new Uint8Array(s.length);
        for (let i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i) & 0xff;
        return bytes;
    }
    function stat(file) {
        const found = probe(file);
        if (!found.exists) throw fsError('ENOENT', 'stat', file);
        return {size: found.size, mtime: new Date(0), mtimeMs: 0,
                isFile: () => true, isDirectory: () => false, isSymbolicLink: () => false};
    }
    const readOnly = op => file => { throw new Error(text('node_fs_readonly', op + ' ' + file)); };
    const later = (cb, fn) => setTimeout(() => { let v, err = null; try { v = fn(); } catch (e) { err = e; } if (cb) cb(err, v); }, 0);
    const nodeFs = Object.freeze({
        existsSync: file => probe(file).exists,
        accessSync: file => { if (!probe(file).exists) throw fsError('ENOENT', 'access', file); },
        statSync: (file, opts) => {
            if (opts && opts.throwIfNoEntry === false && !probe(file).exists) return undefined;
            return stat(file);
        },
        lstatSync: file => stat(file),
        readFileSync,
        exists: (file, cb) => setTimeout(() => cb && cb(probe(file).exists), 0),
        stat: (file, cb) => later(cb, () => stat(file)),
        readFile: (file, opts, cb) => {
            if (typeof opts === 'function') { cb = opts; opts = undefined; }
            later(cb, () => readFileSync(file, opts));
        },
        writeFileSync: readOnly('write'), appendFileSync: readOnly('append'), mkdirSync: readOnly('mkdir'),
        unlinkSync: readOnly('unlink'), renameSync: readOnly('rename'), rmdirSync: readOnly('rmdir'),
        writeFile: readOnly('write'), mkdir: readOnly('mkdir'), unlink: readOnly('unlink'),
    });
    const nodePath = Object.freeze({
        sep: '/',
        join: (...parts) => parts.filter(p => p !== '').join('/').replace(/\\/g, '/').replace(/\/{2,}/g, '/'),
        dirname: p => { const s = String(p).replace(/\\/g, '/').replace(/\/+$/, ''); const i = s.lastIndexOf('/'); return i < 0 ? '.' : (i === 0 ? '/' : s.slice(0, i)); },
        basename: (p, ext) => { let b = String(p).replace(/\\/g, '/').replace(/\/+$/, '').split('/').pop(); if (ext && b.endsWith(ext) && b !== ext) b = b.slice(0, -ext.length); return b; },
        extname: p => { const b = String(p).replace(/\\/g, '/').split('/').pop(); const i = b.lastIndexOf('.'); return i <= 0 ? '' : b.slice(i); },
    });

    // Keep browser storage mode: deliberately no global process or nw objects.
    // Look Outside's optional Ash check asks Node for a username even in browser mode.
    // Some games treat any require() as NW.js and then read process at load time
    // (e.g. Cyclone-Steam); GameCompat turns the shim off for them (nodeShim: false).
    if (config.nodeShim !== false && typeof window.require === 'undefined') {
        window.require = function (module) {
            const name = String(module).replace(/^node:/, '');
            if (name === 'os') return {userInfo: () => ({username: config.playerName || 'Player'})};
            if (name === 'fs') return nodeFs;
            if (name === 'path') return nodePath;
            throw new Error(text('node_module', module));
        };
    }
    // The Android window is already fullscreen. Browser fullscreen requests that do not come
    // from a tap are rejected (GeckoView: "TypeError: Fullscreen request denied"), and MZ stops
    // the game on any unhandled rejection - e.g. CGMZ_Core "Start Fullscreen" in Scene_Boot.
    // Every vendor variant becomes a successful no-op, so F4/options toggles are harmless too.
    const fullscreenDone = () => Promise.resolve();
    const fullscreenApi = [
        [window.Element && Element.prototype, ['requestFullscreen', 'requestFullScreen', 'mozRequestFullScreen', 'webkitRequestFullscreen', 'webkitRequestFullScreen']],
        [window.Document && Document.prototype, ['exitFullscreen', 'cancelFullScreen', 'mozCancelFullScreen', 'webkitExitFullscreen', 'webkitCancelFullScreen']],
    ];
    for (const [owner, names] of fullscreenApi) {
        if (!owner) continue;
        for (const name of names) {
            try { Object.defineProperty(owner, name, {value: fullscreenDone, configurable: true, writable: true}); }
            catch (e) { log('fullscreen ' + name + ': ' + e.message); }
        }
    }
    function pause() {
        clearInput();
        if (window.SceneManager && typeof SceneManager.stop === 'function' && !engineStopped) {
            engineStopped = true;
            SceneManager.stop();
        }
        const ctx = window.WebAudio && WebAudio._context;
        if (ctx && ctx.state === 'running') ctx.suspend().catch(() => {});
    }
    function resume() {
        clearInput();
        fitToViewport();
        if (engineStopped && window.SceneManager && typeof SceneManager.resume === 'function') {
            engineStopped = false;
            SceneManager.resume();
        }
        const ctx = window.WebAudio && WebAudio._context;
        if (ctx && ctx.state === 'suspended') ctx.resume().catch(e => log(e.message));
    }
    async function collectSaves() {
        const local = Object.create(null), forage = Object.create(null);
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i); local[key] = localStorage.getItem(key);
        }
        if (window.localforage) {
            await localforage.ready();
            for (const key of await localforage.keys()) {
                const value = await localforage.getItem(key);
                if (value instanceof Blob || value instanceof ArrayBuffer || ArrayBuffer.isView(value))
                    throw new Error(text('binary_save', key));
                forage[key] = value;
            }
        }
        return {format: 'PadPort saves', version: 1, title: config.title, engine: config.engine, local, forage};
    }
    async function exportSaves() {
        try { host.exportReady(JSON.stringify(await collectSaves())); }
        catch (e) { host.saveError(text('export_error', e.message)); }
    }
    async function importSaves(backup) {
        const previous = await collectSaves();
        if (backup.format !== 'PadPort saves' || backup.version !== 1 || backup.title !== config.title || backup.engine !== config.engine)
            throw new Error(text('wrong_backup'));
        if (!backup.local || !backup.forage || Array.isArray(backup.local) || Array.isArray(backup.forage))
            throw new Error(text('invalid_backup'));
        if (!window.localforage && Object.keys(backup.forage).length) throw new Error(text('no_forage'));
        const localTouched = [], forageTouched = [];
        try {
            for (const [key, value] of Object.entries(backup.local)) {
                if (typeof value !== 'string') throw new Error(text('invalid_local_storage', key));
                localTouched.push(key); localStorage.setItem(key, value);
            }
            for (const [key, value] of Object.entries(backup.forage)) {
                forageTouched.push(key); await localforage.setItem(key, value);
            }
        } catch (error) {
            for (const key of localTouched) {
                if (Object.prototype.hasOwnProperty.call(previous.local, key)) localStorage.setItem(key, previous.local[key]);
                else localStorage.removeItem(key);
            }
            for (const key of forageTouched) {
                if (Object.prototype.hasOwnProperty.call(previous.forage, key)) await localforage.setItem(key, previous.forage[key]);
                else await localforage.removeItem(key);
            }
            throw error;
        }
    }
    async function restore(backup) {
        try { await importSaves(backup); host.importReady(); }
        catch (e) { host.saveError(text('import_error', e.message)); }
    }
    window.PadPort = Object.freeze({poll, inputMode: () => inputMode, clearInput, pause, resume, exportSaves, restore, collectSaves, importSaves, fitToViewport});
    // Synchronize a mode switch before game listeners process the first native key.
    // Otherwise disconnecting the old gamepad could clear that newly pressed key.
    window.addEventListener('keydown', poll, true);
    window.addEventListener('keyup', poll, true);
    window.addEventListener('resize', fitToViewport);
    if (config.engine === 'MV' || config.engine === 'MZ') {
        const waitForGraphics = () => {
            if (!fitToViewport()) window.requestAnimationFrame(waitForGraphics);
        };
        window.requestAnimationFrame(waitForGraphics);
    }
    window.addEventListener('blur', clearInput);
    window.addEventListener('error', e => log('ERROR ' + e.message + ' @ ' + e.filename + ':' + e.lineno));
    window.addEventListener('unhandledrejection', e => log('PROMISE ' + String(e.reason)));
    log(text('gamepad_ready', config.engine || text('tester')));
})();

/* PadPort: fixes for PC (NW.js) plugin code in specific games. Java (GameCompat) picks the profile.
   Runs inside bridge.js, before the game's own scripts. */
(() => {
    'use strict';
    const config = window.__PADPORT_CONFIG__ || {};
    if (config.compat !== 'welcome-to-elderfield' || window.PadPortCompat) return;
    const host = window.PadPortHost;
    const log = message => { try { if (host && host.log) host.log('compat: ' + String(message).slice(0, 500)); } catch (_) {} };
    const encodePath = file => String(file).replace(/\\/g, '/').split('/').filter(Boolean).map(encodeURIComponent).join('/');

    // --- LookupTableComparison: gift tables (Tables/Gifts/*.csv) -------------------------------
    // Under NW.js it lists Tables/ with fs; in a browser it loads only its "Web Preload Files"
    // parameter, which this game leaves empty, so every string.CSV("Gifts/...") check would fail.
    // The file names come from PadPort's index of the game folder; keys match the NW.js scan.
    const tables = Array.isArray(config.tables) ? config.tables.filter(f => typeof f === 'string') : [];
    function tableKey(file) { return file.replace(/\\/g, '/').replace(/^Tables\//i, '').replace(/\.csv$/i, ''); }
    function wrapTables(manager) {
        if (!manager || typeof manager.preloadAll !== 'function' || manager.__padportTables) return;
        Object.defineProperty(manager, '__padportTables', {value: true});
        const original = manager.preloadAll;
        manager.preloadAll = function () {
            const result = original.apply(this, arguments);
            for (const file of tables) {
                const key = tableKey(file);
                if (!key || (this.cacheString && this.cacheString[key])) continue;
                // isDatabaseLoaded() waits until _webFilesLoaded reaches _webFilesTotal.
                this._webFilesTotal = (this._webFilesTotal || 0) + 1;
                fetch(encodePath(file), {cache: 'no-store'})
                    .then(response => { if (!response.ok) throw new Error('HTTP ' + response.status); return response.text(); })
                    .then(text => this.parseAndCache(key, text))
                    .catch(e => log('table ' + file + ': ' + e.message))
                    .finally(() => { this._webFilesLoaded = (this._webFilesLoaded || 0) + 1; });
            }
            return result;
        };
    }
    let tableManager;
    Object.defineProperty(window, 'TableManager', {
        configurable: true, enumerable: true,
        get: () => tableManager,
        set: value => { tableManager = value; wrapTables(value); }
    });

    // --- SimpleMusicPlayer (radio, "The Weather Channel") ------------------------------------
    // isSongFileExists() asks Node for process.mainModule (no try/catch), so choosing a song
    // would stop the game. Playback itself already falls back to the relative
    // media/player/*.ogg path, which PadPort serves with byte ranges.
    const probes = new Map();
    function fileExists(file) {
        const url = encodePath(file);
        if (probes.has(url)) return probes.get(url);
        let found = false;
        try {
            const request = new XMLHttpRequest();
            request.open('HEAD', url, false);
            request.send();
            found = request.status === 200;
        } catch (e) { log('probe ' + file + ': ' + e.message); }
        probes.set(url, found);
        return found;
    }
    function patchScene(scene) {
        const proto = scene && Object.getPrototypeOf(scene);
        if (!proto || !proto.constructor || proto.constructor.name !== 'Scene_MusicPlayer') return;
        if (Object.prototype.hasOwnProperty.call(proto, '__padportSongCheck')) return;
        Object.defineProperty(proto, '__padportSongCheck', {value: true});
        proto.isSongFileExists = fileExists;
    }
    function hookScenes() {
        const manager = window.SceneManager;
        if (!manager || typeof manager.onSceneCreate !== 'function' || manager.__padportCompat) return false;
        Object.defineProperty(manager, '__padportCompat', {value: true});
        const original = manager.onSceneCreate;
        manager.onSceneCreate = function () {
            try { patchScene(this._scene); } catch (e) { log('scene: ' + e.message); }
            return original.apply(this, arguments);
        };
        return true;
    }
    // Plugins finish loading before the window load event (FOSSIL and main.js add them first).
    window.addEventListener('load', () => { if (!hookScenes()) log('SceneManager not found'); });

    window.PadPortCompat = Object.freeze({profile: config.compat, tableKey, fileExists, patchScene, hookScenes});
})();

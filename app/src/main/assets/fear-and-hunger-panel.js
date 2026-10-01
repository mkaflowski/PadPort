/* Fear & Hunger lower-screen panel: read-only party status (body, mind, conditions, states, gear). */
(() => {
    'use strict';
    const text = window.PadPortI18n.text;
    document.documentElement.lang = PadPortI18n.language;
    const el = id => document.getElementById(id);
    const node = (tag, value, css) => { const n = document.createElement(tag); if (value != null) n.textContent = value; if (css) n.className = css; return n; };
    const cards = new Map();
    let state = null, fontKey = '', fontGeneration = 0, gameFaces = [];

    function applyGameFonts(paths) {
        if (typeof FontFace !== 'function' || !document.fonts) return;
        const files = Array.isArray(paths) ? paths.filter(p => typeof p === 'string').slice(0, 8) : [];
        const key = JSON.stringify(files);
        if (key === fontKey) return;
        fontKey = key;
        const generation = ++fontGeneration;
        document.documentElement.style.removeProperty('--game-font');
        for (const face of gameFaces) document.fonts.delete(face);
        gameFaces = [];
        Promise.all(files.map((file, i) => new FontFace('PadPortGameFont' + generation + '_' + i,
            'url(' + JSON.stringify('game-font?path=' + encodeURIComponent(file)) + ')').load().catch(() => null)))
            .then(faces => {
                if (generation !== fontGeneration) return;   // a newer font request won
                gameFaces = faces.filter(Boolean);
                for (const face of gameFaces) document.fonts.add(face);
                if (gameFaces.length) document.documentElement.style.setProperty('--game-font', gameFaces.map(f => f.family).join(', ') + ', serif');
            }).catch(() => {});
    }
    function image(key, css) {
        const url = state.images?.[key];
        if (!url?.startsWith('data:image/png;base64,')) return null;
        const img = node('img', null, css); img.src = url; img.alt = ''; return img;
    }
    function makeCard(id) {
        const record = {id, card: node('div', null, 'actor'), photo: node('span', null, 'face'), name: node('div', null, 'name'),
            gauges: [], conditions: node('div', null, 'conditions'), states: node('div', null, 'states'), gear: node('div', null, 'gear'),
            imageUrl: '', pendingUrl: '', imageKey: '', imageGeneration: 0};
        record.photo.hidden = true;
        record.card.append(record.photo, record.name);
        for (const css of ['body', 'mind']) {
            const label = node('div', null, 'gauge'), bar = node('progress', null, css);
            record.gauges.push({label, bar}); record.card.append(label, bar);
        }
        record.card.append(record.conditions, record.states, record.gear);
        record.card.setAttribute('role', 'group');
        return record;
    }
    /** Stable portraits: decode the new face off-screen, keep the last one on errors or missing pixels. */
    function updatePortrait(record, key) {
        if (key && key !== record.imageKey) { record.imageKey = key; record.imageGeneration++; record.pendingUrl = ''; }
        const url = state.images?.[key];
        if (!url?.startsWith('data:image/png;base64,') || url === record.imageUrl || url === record.pendingUrl) return;
        const generation = ++record.imageGeneration;
        record.pendingUrl = url;
        const img = node('img'); img.alt = ''; img.decoding = 'async';
        const current = () => cards.get(record.id) === record && record.imageGeneration === generation;
        const failed = () => { if (current()) record.pendingUrl = ''; };
        img.onerror = failed;
        img.onload = async () => {
            try {
                if (!(img.naturalWidth > 0)) { failed(); return; }
                if (typeof img.decode === 'function') await img.decode();
                if (!current()) return;
                record.photo.replaceChildren(img); record.photo.hidden = false;
                record.imageUrl = url; record.pendingUrl = '';
            } catch (_) { failed(); }
        };
        img.src = url;
    }
    /** Same hit shake as the Look Outside panel; skipped when the system asks for reduced motion. */
    function animateDamage(record) {
        if (typeof record.card.animate !== 'function' || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
        record.hitAnimation?.cancel();
        record.hitAnimation = record.card.animate(
            [0, -4, 4, -3, 3, -1, 0].map(x => ({transform: 'translateX(' + x + 'px)'})),
            {duration: 200, easing: 'ease-out'}
        );
    }
    function updateCard(record, actor, data, sameBattle) {
        const damaged = sameBattle && Number.isFinite(record.actor?.hp) && Number.isFinite(actor.hp) && actor.hp < record.actor.hp;
        if (!sameBattle && record.hitAnimation) { record.hitAnimation.cancel(); record.hitAnimation = null; }
        record.actor = actor;
        record.card.className = 'actor' + (actor.dead ? ' dead' : '');
        if (record.name.textContent !== actor.name) record.name.textContent = actor.name;
        record.photo.hidden = !record.imageUrl && !actor.portrait;
        updatePortrait(record, actor.portrait);
        [[actor.hp, actor.mhp, data.hpLabel], [actor.mp, actor.mmp, data.mpLabel]].forEach(([value, max, label], i) => {
            const gauge = record.gauges[i], caption = (label || '') + ' ' + value + '/' + max;
            if (gauge.label.textContent !== caption) gauge.label.textContent = caption;
            gauge.bar.max = Math.max(1, Number(max) || 1); gauge.bar.value = Math.max(0, Number(value) || 0);
        });
        const conditions = (actor.conditions || []).join(' · ');
        if (record.conditions.textContent !== conditions) record.conditions.textContent = conditions;
        const statesKey = JSON.stringify((actor.states || []).map(s => [s.name, s.icon, data.images?.[s.icon]]));
        if (record.statesKey !== statesKey) {
            record.statesKey = statesKey; record.states.replaceChildren();
            for (const status of actor.states || []) {
                const entry = node('span'); const picture = image(status.icon, 'icon');
                if (picture) entry.append(picture);
                entry.append(node('span', status.name)); record.states.append(entry);
            }
        }
        const gearKey = JSON.stringify((actor.equipment || []).map(item => [item.name, item.icon, data.images?.[item.icon]]));
        if (record.gearKey !== gearKey) {
            record.gearKey = gearKey; record.gear.replaceChildren();
            (actor.equipment || []).forEach((item, index) => {
                if (index) record.gear.append(node('span', ' · '));
                const entry = node('span', null, 'gear-item'), picture = image(item.icon, 'icon');
                if (picture) entry.append(picture);
                entry.append(node('span', item.name)); record.gear.append(entry);
            });
        }
        return damaged;
    }
    function update(data) {
        const previousState = state;
        state = data;
        // Only a drop within one running battle shakes the card (not loading a save or a new fight).
        const sameBattle = data.mode === 'battle' && previousState?.mode === 'battle' &&
            data.epoch === previousState.epoch && data.scene === previousState.scene;
        applyGameFonts(data.fonts);
        if (data.title && el('gameTitle').textContent !== data.title) el('gameTitle').textContent = data.title;
        const waiting = data.mode === 'waiting';
        el('waiting').hidden = !waiting;
        if (waiting) el('waiting').textContent = text('dual_waiting');
        const map = waiting ? '' : data.map || '';
        if (el('mapName').textContent !== map) el('mapName').textContent = map;
        const party = el('party'), seen = new Set();
        (waiting ? [] : data.party || []).forEach((actor, index) => {
            let record = cards.get(actor.id);
            if (!record) { record = makeCard(actor.id); cards.set(actor.id, record); }
            seen.add(actor.id);
            const damaged = updateCard(record, actor, data, sameBattle);
            if (party.children[index] !== record.card) party.insertBefore(record.card, party.children[index] || null);
            if (damaged) animateDamage(record);
        });
        // Title / game over / new playthrough: no portraits of the previous party remain.
        for (const [id, record] of cards) if (!seen.has(id)) {
            record.imageGeneration++; record.hitAnimation?.cancel(); record.card.remove(); cards.delete(id);
        }
    }
    window.GameCompanionPanel = Object.freeze({update});
    GameCompanionHost.ready();
})();

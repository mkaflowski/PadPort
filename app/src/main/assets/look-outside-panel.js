(() => {
    'use strict';
    const text = window.PadPortI18n.text;
    document.documentElement.lang = PadPortI18n.language;
    const el = id => document.getElementById(id);
    const logo = el('gameLogo'), title = el('gameTitle');
    const showLogo = loaded => { logo.hidden = !loaded; title.hidden = loaded; };
    showLogo(false); // Keep the title visible while offline, loading, or after an image error.
    logo.onload = () => showLogo(logo.naturalWidth > 0);
    logo.onerror = () => showLogo(false);
    if (window.__PADPORT_CONFIG__?.logoUrl) logo.src = window.__PADPORT_CONFIG__.logoUrl;
    const node = (tag, value, css) => { const n = document.createElement(tag); if (value != null) n.textContent = value; if (css) n.className = css; return n; };
    let state = null;
    const actorCards = new Map();
    let inventoryButton = null, inventoryMounted = false;
    const inventoryAvailable = data => !!data && data.mode !== 'waiting' && data.mode !== 'inventory' && data.canOpen === true;
    let fontKey = '', fontGeneration = 0, gameFaces = [];
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
        Promise.all(files.map((file, i) => {
            const face = new FontFace('PadPortGameFont' + generation + '_' + i,
                'url(' + JSON.stringify('game-font?path=' + encodeURIComponent(file)) + ')');
            return face.load().catch(() => null);
        })).then(faces => {
            if (generation !== fontGeneration) return;
            gameFaces = faces.filter(Boolean);
            for (const face of gameFaces) document.fonts.add(face);
            if (gameFaces.length) document.documentElement.style.setProperty('--game-font', gameFaces.map(f => f.family).join(', ') + ', sans-serif');
        }).catch(() => {});
    }
    const send = (action, args = {}) => {
        if (state) {
            GameCompanionHost.command(JSON.stringify({action, ...args, epoch: state.epoch, scene: state.scene}));
            // Selecting the same item may not produce a new snapshot from the game.
            if (action === 'select' && state.mode === 'inventory' && state.selected === args.key) scrollToDescription();
        }
    };
    function scrollToDescription() {
        if (!el('description').hidden) el('description').scrollIntoView({block: 'start', behavior: 'auto'});
    }
    function button(label, action, args, enabled = true) {
        const b = node('button', label); b.disabled = !enabled; b.onclick = () => { if (!b.disabled) send(action, args); }; return b;
    }
    function image(key, css) {
        const url = state.images?.[key];
        if (!url?.startsWith('data:image/png;base64,')) return node('span');
        const img = node('img', null, css); img.src = url; img.alt = ''; return img;
    }
    function makeActorCard(id) {
        const record = {id, card: node('div', null, 'actor'), photo: node('span', null, 'face'),
            name: node('div', null, 'name'), statuses: node('div'), gear: node('div', null, 'gear'),
            stats: [], imageUrl: '', pendingUrl: '', imageKey: '', imageGeneration: 0};
        record.photo.hidden = true;
        record.card.append(record.photo, record.name);
        for (const css of ['', 'mp']) {
            const label = node('div'), bar = node('progress', null, css);
            record.stats.push({label, bar}); record.card.append(label, bar);
        }
        record.card.append(record.statuses, record.gear);
        const choose = () => {
            if (actorCards.get(id) === record && state?.target && record.actor.target) send('actor', {id});
        };
        record.card.onclick = choose;
        record.card.onkeydown = event => {
            if (state?.target && record.actor.target && (event.key === 'Enter' || event.key === ' ')) {
                event.preventDefault(); choose();
            }
        };
        return record;
    }
    function updatePortrait(record, key) {
        if (key && key !== record.imageKey) {
            record.imageKey = key; record.imageGeneration++; record.pendingUrl = '';
        }
        const url = state.images?.[key];
        // Missing/not-yet-ready pixels are not an instruction to erase the last portrait.
        if (!url?.startsWith('data:image/png;base64,') || url === record.imageUrl || url === record.pendingUrl) return;
        const generation = ++record.imageGeneration;
        record.pendingUrl = url;
        const img = node('img'); img.alt = ''; img.decoding = 'async';
        const current = () => actorCards.get(record.id) === record && record.imageGeneration === generation;
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
    function animateDamage(record) {
        if (typeof record.card.animate !== 'function' || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
        record.hitAnimation?.cancel();
        record.hitAnimation = record.card.animate(
            [0, -4, 4, -3, 3, -1, 0].map(x => ({transform: 'translateX(' + x + 'px)'})),
            {duration: 200, easing: 'ease-out'}
        );
    }
    function updateParty(data, previousState) {
        const party = el('party'), seen = new Set();
        const sameBattle = data.mode === 'battle' && previousState?.mode === 'battle' &&
            data.epoch === previousState.epoch && data.scene === previousState.scene;
        for (const [index, actor] of (data.party || []).entries()) {
            let record = actorCards.get(actor.id);
            if (!record) { record = makeActorCard(actor.id); actorCards.set(actor.id, record); }
            const damaged = sameBattle && Number.isFinite(record.actor?.hp) && Number.isFinite(actor.hp) && actor.hp < record.actor.hp;
            if (!sameBattle && record.hitAnimation) { record.hitAnimation.cancel(); record.hitAnimation = null; }
            seen.add(actor.id); record.actor = actor;
            record.card.className = 'actor' + (actor.selected ? ' selected' : '') +
                (data.target ? ' actor-target' : '') + (data.target && !actor.target ? ' actor-unavailable' : '');
            record.card.setAttribute('role', data.target ? 'button' : 'group');
            if (data.target) record.card.setAttribute('aria-disabled', String(!actor.target));
            else record.card.removeAttribute('aria-disabled');
            record.card.tabIndex = data.target && actor.target ? 0 : -1;
            if (record.name.textContent !== actor.name) record.name.textContent = actor.name;
            record.photo.hidden = !record.imageUrl && !actor.portrait;
            updatePortrait(record, actor.portrait);
            const gauges = [[actor.hp, actor.mhp, data.hpLabel], [actor.mp, actor.mmp, data.mpLabel]];
            gauges.forEach(([value, max, label], i) => {
                const gauge = record.stats[i], caption = label + ' ' + value + '/' + max;
                if (gauge.label.textContent !== caption) gauge.label.textContent = caption;
                gauge.bar.max = Math.max(1, max); gauge.bar.value = Math.max(0, value);
            });
            const statuses = JSON.stringify((actor.states || []).map(s => [s.name, s.icon, data.images?.[s.icon]]));
            if (record.statusesKey !== statuses) {
                record.statusesKey = statuses; record.statuses.replaceChildren();
                for (const status of actor.states || []) {
                    const line = node('span', status.name); line.prepend(image(status.icon, 'icon')); record.statuses.append(line);
                }
            }
            const equipment = (actor.equipment || []).map(item => typeof item === 'string' ? {name: item, icon: ''} : item);
            const gearKey = JSON.stringify(equipment.map(item => [item.name, item.icon, data.images?.[item.icon]]));
            if (record.gearKey !== gearKey) {
                record.gearKey = gearKey; record.gear.replaceChildren();
                equipment.forEach((item, index) => {
                    if (index) record.gear.append(node('span', ' · '));
                    const entry = node('span', null, 'gear-item');
                    if (data.images?.[item.icon]?.startsWith('data:image/png;base64,')) entry.append(image(item.icon, 'icon'));
                    entry.append(node('span', item.name)); record.gear.append(entry);
                });
            }
            if (party.children[index] !== record.card) party.insertBefore(record.card, party.children[index] || null);
            if (damaged) animateDamage(record);
        }
        for (const [id, record] of actorCards) if (!seen.has(id)) {
            record.imageGeneration++; record.hitAnimation?.cancel(); record.card.remove(); actorCards.delete(id);
        }
    }
    function update(data) {
        const selectionChanged = state?.mode === 'inventory' && data.mode === 'inventory' &&
            state.epoch === data.epoch && state.scene === data.scene && state.category === data.category &&
            state.selected !== data.selected && !!data.selected;
        const scroll = window.scrollY, previousState = state; state = data;
        applyGameFonts(data.fonts);
        ['nav', 'items'].forEach(id => el(id).replaceChildren());
        if (data.mode === 'inventory' || data.mode === 'waiting') {
            el('actions').replaceChildren(); inventoryMounted = false;
        }
        el('description').hidden = true;
        updateParty(data, previousState);
        if (data.mode === 'inventory') {
            for (const category of data.categories || []) {
                const b = button(category.name, 'category', {key: category.key}, category.enabled);
                if (data.category === category.key) b.className = 'selected'; el('nav').append(b);
            }
            for (const item of data.items || []) {
                const b = button('', 'select', {key: item.key}, data.interactive && !data.target);
                b.className = 'item' + (data.selected === item.key ? ' selected' : '');
                b.append(image(item.icon, 'icon'), node('span', item.name), node('span', '×' + item.count, 'count')); el('items').append(b);
            }
            const selected = data.items.find(item => item.key === data.selected);
            if (selected) { el('description').hidden = false; el('description').textContent = selected.name + '\n' + selected.description; }
            if (!data.target) el('actions').append(button(text('dual_use'), 'use', {key: data.selected}, data.canUse));
            el('actions').append(button(text('dual_back'), 'back', {}, data.interactive));
        } else if (data.mode !== 'waiting') {
            if (!inventoryButton) {
                inventoryButton = button('', 'inventory', {}, false);
                inventoryButton.className = 'inventory-open';
            }
            const label = data.labels?.inventory || text('dual_inventory');
            if (inventoryButton.textContent !== label) inventoryButton.textContent = label;
            inventoryButton.disabled = !inventoryAvailable(data);
            if (!inventoryMounted) { el('actions').replaceChildren(inventoryButton); inventoryMounted = true; }
        }
        if (selectionChanged && !el('description').hidden) scrollToDescription();
        else window.scrollTo(0, scroll);
    }
    window.GameCompanionPanel = Object.freeze({update});
    GameCompanionHost.ready();
})();

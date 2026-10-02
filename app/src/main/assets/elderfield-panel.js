/* Welcome to Elderfield lower-screen panel: clock/weather/gold, character card and a remote
   for the game's own item menu. The sky follows the in-game time of day, season and weather. */
(() => {
    'use strict';
    const text = window.PadPortI18n.text;
    document.documentElement.lang = PadPortI18n.language;
    const el = id => document.getElementById(id);
    const node = (tag, value, css) => { const n = document.createElement(tag); if (value != null) n.textContent = value; if (css) n.className = css; return n; };
    const reducedMotion = () => !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const cards = new Map();
    let state = null, fontKey = '', fontGeneration = 0, gameFaces = [], inventoryButton = null;

    // Sky colours per day phase (game variable "Day Phase": 1 dawn, 2 day, 3 dusk, 4 night).
    const PHASES = {1: ['#3c3f74', '#e09a72'], 2: ['#2f6fa8', '#a6d0de'], 3: ['#3a2350', '#c4583c'], 4: ['#060a1a', '#1a2342']};
    // Seasons (Rebirth, the Harvest, the Witch, Death): a soft glow from the bottom.
    const SEASONS = ['rgba(92,170,84,.45)', 'rgba(214,160,58,.45)', 'rgba(150,70,170,.45)', 'rgba(170,196,214,.40)'];
    const DIM = {clear: 0, cloudy: .18, rain: .3, storm: .45, snow: .08};
    const WEATHER = ['clear', 'cloudy', 'rain', 'storm', 'snow'];

    function phaseOf(sky) {
        if (PHASES[sky.phase]) return sky.phase;
        const h = Number(sky.hour) || 0;
        return h >= 6 && h < 9 ? 1 : h >= 9 && h < 18 ? 2 : h >= 18 && h < 21 ? 3 : 4;
    }
    function applySky(sky) {
        if (!sky) return;
        const root = document.body.style, [top, bottom] = PHASES[phaseOf(sky)];
        root.setProperty('--sky-top', top); root.setProperty('--sky-bottom', bottom);
        root.setProperty('--season', SEASONS[sky.season] || 'rgba(0,0,0,0)');
        const weather = WEATHER.includes(sky.weather) ? sky.weather : 'clear';
        root.setProperty('--dim', String(DIM[weather]));
        for (const name of WEATHER) document.body.classList.toggle(name, name === weather);
        document.body.dataset.phase = String(phaseOf(sky));
    }
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
                if (generation !== fontGeneration) return;
                gameFaces = faces.filter(Boolean);
                for (const face of gameFaces) document.fonts.add(face);
                if (gameFaces.length) document.documentElement.style.setProperty('--game-font', gameFaces.map(f => f.family).join(', ') + ', sans-serif');
            }).catch(() => {});
    }
    const send = (action, args = {}) => {
        if (state) GameCompanionHost.command(JSON.stringify({action, ...args, epoch: state.epoch, scene: state.scene}));
    };
    function button(label, action, args, enabled = true) {
        const b = node('button', label); b.disabled = !enabled; b.onclick = () => { if (!b.disabled) send(action, args); }; return b;
    }
    function image(key, css) {
        const url = state.images?.[key];
        if (!url?.startsWith('data:image/png;base64,')) return null;
        const img = node('img', null, css); img.src = url; img.alt = ''; return img;
    }
    function chips(target, list, cacheKey, record) {
        const signature = JSON.stringify((list || []).map(entry => [entry.name, entry.icon, state.images?.[entry.icon]]));
        if (record[cacheKey] === signature) return;
        record[cacheKey] = signature; target.replaceChildren();
        for (const entry of list || []) {
            const chip = node('span', null, 'chip'), picture = image(entry.icon, 'icon');
            if (picture) chip.append(picture);
            chip.append(node('span', entry.name)); target.append(chip);
        }
    }
    function pulse(element) {
        if (reducedMotion()) return;
        element.classList.remove('changed'); void element.offsetWidth; element.classList.add('changed');
    }

    // --- Character card ---------------------------------------------------------------------
    function makeCard(id) {
        const record = {id, card: node('div', null, 'actor'), photo: node('div', null, 'face'), name: node('div', null, 'name'),
            title: node('span'), level: node('span', null, 'level'), bars: [], energy: node('div', null, 'energy'),
            states: node('div', null, 'states'), gear: node('div', null, 'gear'), imageUrl: '', pendingUrl: '', imageKey: '', imageGeneration: 0};
        record.name.append(record.title, record.level);
        record.card.append(record.photo, record.name);
        for (const css of ['hp', 'mp']) {
            const label = node('div', null, 'gauge'), bar = node('div', null, 'bar ' + css), fill = node('i');
            bar.append(fill); label.append(bar);
            const caption = node('span'); label.prepend(caption);
            record.bars.push({caption, fill}); record.card.append(label);
        }
        record.card.append(record.energy, record.states, record.gear);
        record.card.setAttribute('role', 'group');
        return record;
    }
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
                record.photo.replaceChildren(img); record.imageUrl = url; record.pendingUrl = '';
            } catch (_) { failed(); }
        };
        img.src = url;
    }
    function animateDamage(record) {
        if (typeof record.card.animate !== 'function' || reducedMotion()) return;
        record.hitAnimation?.cancel();
        record.hitAnimation = record.card.animate([0, -4, 4, -3, 3, -1, 0].map(x => ({transform: 'translateX(' + x + 'px)'})),
            {duration: 200, easing: 'ease-out'});
    }
    function updateCards(data, previous) {
        const party = el('party'), seen = new Set();
        const sameBattle = data.mode === 'battle' && previous?.mode === 'battle' && data.epoch === previous.epoch && data.scene === previous.scene;
        (data.party || []).forEach((actor, index) => {
            let record = cards.get(actor.id);
            if (!record) { record = makeCard(actor.id); cards.set(actor.id, record); }
            const damaged = sameBattle && Number.isFinite(record.actor?.hp) && Number.isFinite(actor.hp) && actor.hp < record.actor.hp;
            if (!sameBattle && record.hitAnimation) { record.hitAnimation.cancel(); record.hitAnimation = null; }
            const energyChanged = Number.isFinite(record.energyValue) && record.energyValue !== data.hud?.energy && data.epoch === previous?.epoch;
            seen.add(actor.id); record.actor = actor;
            if (record.title.textContent !== actor.name) record.title.textContent = actor.name;
            const level = (data.levelLabel || 'Lv') + ' ' + actor.level;
            if (record.level.textContent !== level) record.level.textContent = level;
            updatePortrait(record, actor.portrait);
            [[actor.hp, actor.mhp, data.hpLabel], [actor.mp, actor.mmp, data.mpLabel]].forEach(([value, max, label], i) => {
                const bar = record.bars[i], caption = (label || '') + ' ' + value + '/' + max;
                if (bar.caption.textContent !== caption) bar.caption.textContent = caption;
                const width = Math.max(0, Math.min(100, (Number(value) || 0) * 100 / Math.max(1, Number(max) || 1))).toFixed(1) + '%';
                if (bar.fill.style.width !== width) bar.fill.style.width = width;
            });
            const energy = data.hud ? '⚡ ' + (data.hud.energyLabel || '') + ' ' + data.hud.energy : '';
            if (record.energy.textContent !== energy) record.energy.textContent = energy;
            record.energy.hidden = !energy;
            if (energyChanged) pulse(record.energy);
            record.energyValue = data.hud?.energy;
            chips(record.states, actor.states, 'statesKey', record);
            chips(record.gear, actor.equipment, 'gearKey', record);
            if (party.children[index] !== record.card) party.insertBefore(record.card, party.children[index] || null);
            if (damaged) animateDamage(record);
        });
        for (const [id, record] of cards) if (!seen.has(id)) {
            record.imageGeneration++; record.hitAnimation?.cancel(); record.card.remove(); cards.delete(id);
        }
    }

    // --- Clock, weather, gold -----------------------------------------------------------------
    function updateHud(data, previous) {
        const hud = data.hud;
        el('hud').hidden = !hud;
        if (!hud) return;
        const set = (id, value) => { if (el(id).textContent !== value) el(id).textContent = value; };
        set('clock', hud.time || ''); set('date', hud.date || '');
        set('weather', text('weather_' + (WEATHER.includes(hud.sky?.weather) ? hud.sky.weather : 'clear')));
        const gold = el('gold'), label = hud.gold + ' ' + (hud.currency || '');
        if (gold.firstChild?.nodeType !== 3) gold.prepend(document.createTextNode(''));
        if (gold.firstChild.textContent !== label) gold.firstChild.textContent = label;
        const before = previous?.hud?.gold;
        if (Number.isFinite(before) && before !== hud.gold && previous.epoch === data.epoch && !reducedMotion()) {
            const change = hud.gold - before, delta = node('span', (change > 0 ? '+' : '') + change, 'delta ' + (change > 0 ? 'plus' : 'minus'));
            delta.addEventListener('animationend', () => delta.remove());
            gold.append(delta);
        }
        applySky(hud.sky);
    }

    // --- The game's item menu -------------------------------------------------------------------
    function updateInventory(data, previous) {
        const open = data.mode === 'inventory';
        el('inventory').hidden = !open;
        el('popup').hidden = !(open && data.popup);
        el('nav').replaceChildren(); el('items').replaceChildren(); el('choices').replaceChildren();
        el('description').hidden = true;
        if (!open) return;
        el('slots').textContent = Array.isArray(data.slots) ? '🎒 ' + data.slots[0] + '/' + data.slots[1] : '';
        for (const category of data.categories || []) {
            const b = button(category.name, 'category', {key: category.key}, category.enabled);
            if (data.category === category.key) b.className = 'selected';
            el('nav').append(b);
        }
        const counts = new Map((previous?.mode === 'inventory' && previous.scene === data.scene ? previous.items || [] : []).map(i => [i.key, i.count]));
        for (const item of data.items || []) {
            const b = button('', 'select', {key: item.key}, data.interactive && !data.popup);
            b.className = 'item' + (data.selected === item.key ? ' selected' : '') + (item.usable ? '' : ' inert');
            const picture = image(item.icon, 'icon');
            if (picture) b.append(picture);
            b.append(node('span', item.name, 'label'), node('span', '×' + item.count, 'count'));
            el('items').append(b);
            if (counts.has(item.key) && counts.get(item.key) !== item.count) pulse(b);
        }
        const selected = (data.items || []).find(item => item.key === data.selected);
        if (selected) {
            el('description').hidden = false;
            el('description').textContent = selected.name + (selected.description ? '\n' + selected.description : '');
        }
        if (data.popup) {
            const title = data.popup.kind === 'target' ? text('dual_choose_target')
                : data.popup.kind === 'confirm' ? text('dual_confirm_drop') + (data.popup.item ? ' ' + data.popup.item + ' ×' + data.popup.amount : '')
                : (data.popup.item || text('dual_choose_action'));
            el('popupTitle').textContent = title;
            for (const choice of data.popup.choices || []) el('choices').append(button(choice.name, 'choice', {index: choice.index}, choice.enabled));
        }
    }
    function updateActions(data) {
        const actions = el('actions');
        if (data.mode === 'waiting') { actions.replaceChildren(); return; }
        if (data.mode === 'inventory') {
            const selected = (data.items || []).find(item => item.key === data.selected);
            const children = [];
            if (!data.popup) children.push(button(selected?.action || text('dual_use'), 'use', {key: data.selected}, !!data.canUse));
            children.push(button(text('dual_back'), 'back', {}, data.interactive));
            actions.replaceChildren(...children);
            return;
        }
        if (!inventoryButton) { inventoryButton = button('', 'inventory', {}, false); inventoryButton.className = 'inventory-open'; }
        const label = data.labels?.inventory || text('dual_inventory');
        if (inventoryButton.textContent !== label) inventoryButton.textContent = label;
        inventoryButton.disabled = !(data.canOpen === true);
        if (actions.firstChild !== inventoryButton || actions.childNodes.length !== 1) actions.replaceChildren(inventoryButton);
    }
    function update(data) {
        const previous = state; state = data;
        applyGameFonts(data.fonts);
        const waiting = data.mode === 'waiting';
        el('waiting').hidden = !waiting;
        if (waiting) el('waiting').textContent = text('dual_waiting');
        updateHud(waiting ? {} : data, previous);
        updateCards(waiting ? {party: []} : data, previous);
        updateInventory(data, previous);
        updateActions(data);
    }
    window.GameCompanionPanel = Object.freeze({update});
    GameCompanionHost.ready();
})();

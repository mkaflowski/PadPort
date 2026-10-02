/* PadPort host for engines without addJavascriptInterface (GeckoView).
   Controller state arrives as Server-Sent Events from the app's loopback server;
   calls back to the app are small POST requests. Same interface as the WebView host. */
(() => {
    'use strict';
    if (window.PadPortHost) return;
    let snapshot = '{"revision":0,"paused":false,"pads":[]}';
    const post = (name, body) => fetch('__padport__/' + name, {method: 'POST', body: body == null ? '' : String(body)}).catch(() => {});
    const events = new EventSource('__padport__/events');
    events.onmessage = e => { snapshot = e.data; };
    events.addEventListener('cmd', e => {
        const pad = window.PadPort;
        if (!pad) return;
        const command = String(e.data);
        if(command==='dual-disable'){if(window.LookOutsideDual)LookOutsideDual.disable();if(window.FearHungerDual)FearHungerDual.disable();if(window.ElderfieldDual)ElderfieldDual.disable();return;}
        if (command === 'pause') pad.pause();
        else if (command === 'resume') pad.resume();
        else if (command === 'exportSaves') pad.exportSaves();
        else if (command.startsWith('restore:')) {
            try { pad.restore(JSON.parse(command.slice(8))); } catch (error) { post('save-error', error.message); }
        }
    });
    window.PadPortHost = {
        dualPoll: () => fetch('__padport__/dual-poll',{cache:'no-store'}).then(r=>r.text()),
        dualSnapshot: payload => post('dual-snapshot',payload),
        snapshot: () => snapshot,
        log: message => post('log', message),
        exportReady: payload => post('export', payload),
        importReady: () => post('imported', ''),
        saveError: message => post('save-error', message),
    };
})();

(() => {
    'use strict';
    const text=PadPortI18n.text,el=id=>document.getElementById(id);
    document.documentElement.lang=PadPortI18n.language;
    const node=(tag,value)=>{const n=document.createElement(tag);if(value!=null)n.textContent=value;return n;};
    let state={mode:'title'},tab='notes',selection=null;
    const labels={characters:'ttm_characters',notes:'ttm_notes',items:'ttm_items'};
    function render(){
        const scroll=window.scrollY,ready=state.mode==='notebook';
        for(const id of ['tabs','entries','detail'])el(id).replaceChildren();
        el('tabs').hidden=!ready;el('detail').hidden=true;el('empty').textContent='';
        el('message').textContent=text(ready?'ttm_notebook':'ttm_title_hint');
        if(!ready){selection=null;window.scrollTo(0,0);return;}
        for(const [key,label] of Object.entries(labels)){
            const b=node('button',text(label)+' ('+(state[key]||[]).length+')');b.className=tab===key?'selected':'';
            b.onclick=()=>{tab=key;selection=null;render();};el('tabs').append(b);
        }
        const entries=state[tab]||[];
        if(!entries.length)el('empty').textContent=text('ttm_empty');
        if(selection!=null&&!entries.some(item=>item.id===selection))selection=null;
        for(const item of entries){
            const b=node('button',item.name);b.className=selection===item.id?'selected':'';
            if(item.count>1)b.append(node('small','×'+item.count));
            b.onclick=()=>{selection=item.id;render();};el('entries').append(b);
        }
        const selected=entries.find(item=>item.id===selection);
        if(selected){el('detail').hidden=false;el('detail').append(node('h2',selected.name),node('div',selected.description));}
        window.scrollTo(0,scroll);
    }
    window.GameCompanionPanel=Object.freeze({update(data){state=data;render();}});
    render();GameCompanionHost.ready();
})();

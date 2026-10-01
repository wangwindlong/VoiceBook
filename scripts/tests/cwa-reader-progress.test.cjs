// Usage: node scripts/tests/cwa-reader-progress.test.cjs /path/to/patched/epub-progress.js
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(process.argv[2], 'utf8');

async function restore({local = '3', localTime, remote = 10, remoteTime = 2000, bookmark = '', fail = false}) {
    const key = 'calibre.reader.progress./book.epub';
    const storage = new Map();
    if (local !== null) storage.set(key, local);
    if (localTime !== undefined) storage.set(key + '.updatedAt', String(localTime));
    let queueCallback;
    let current = 0;
    const displayed = [];
    const listeners = {};
    const progress = {textContent: ''};
    const config = {bookUrl:'/book.epub', kosyncPercent:remote,
        kosyncUpdatedAt: new Date(remoteTime).toISOString(), bookmark};
    const context = {
        console: {warn() {}}, Event: class { constructor(type) { this.type = type; } },
        calibre: config,
        window: {calibre:config, addEventListener(name, cb) { listeners[name] = cb; },
            dispatchEvent(event) { listeners[event.type]?.(); }},
        history: {pushState() {},replaceState() {}},
        localStorage: {getItem(k) { return storage.get(k) ?? null; }, setItem(k,v) { storage.set(k,String(v)); }},
        document: {getElementById() { return progress; }},
        reader: {rendition: {q:{running:undefined},location:{end:{cfi:'location'}},
            async display(cfi) { if (fail) throw Error('display failed'); displayed.push(cfi); current=cfi; listeners.locationchange?.(); }}},
        ePub() { return {locations: {async generate() {},cfiFromPercentage(p) { return p === 0 ? 'start' : p; },
            percentageFromCfi() { return current === 'start' ? 0 : current; }}}; },
        setInterval(cb) { queueCallback=cb; return 1; },clearInterval() {},
    };
    vm.runInNewContext(source,context);
    // Reader's initial location event must not overwrite the old position before loading finishes.
    listeners.locationchange();
    assert.equal(storage.get(key) ?? null, local);
    queueCallback();
    await new Promise(setImmediate);
    return {displayed,storage,key,progress,listeners,setPosition(p) { current=p; }};
}

(async () => {
    let result = await restore({localTime:1000});
    assert.deepEqual(result.displayed,[0.1]);
    assert.equal(result.storage.get(result.key),'10');
    assert.equal(result.storage.get(result.key+'.updatedAt'),'2000');
    assert.equal(result.progress.textContent,'10%');
    result.setPosition(0.12); result.listeners.locationchange();
    assert.equal(result.storage.get(result.key),'12');
    assert.ok(Number(result.storage.get(result.key+'.updatedAt')) > 2000);
    // Existing bookmarks and untimestamped cache from older CWA must not hide newer server state.
    result = await restore({bookmark:'epubcfi(/6/2)'});
    assert.deepEqual(result.displayed,[0.1]);
    result = await restore({localTime:3000,local:'15'});
    assert.deepEqual(result.displayed,[0.15]);
    result = await restore({localTime:1000,remote:0});
    assert.deepEqual(result.displayed,['start']);
    result = await restore({localTime:1000,remote:null});
    assert.deepEqual(result.displayed,[0.03]);
    result = await restore({local:null,remote:10});
    assert.deepEqual(result.displayed,[0.1]);
    result = await restore({fail:true});
    assert.equal(result.storage.get(result.key),'3');
    console.log('CWA web restore: 7 scenarios passed');
})().catch(error => { console.error(error); process.exitCode=1; });

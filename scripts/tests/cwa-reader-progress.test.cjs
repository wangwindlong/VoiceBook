// Usage: node scripts/tests/cwa-reader-progress.test.cjs /path/to/patched/epub-progress.js
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(process.argv[2], 'utf8');

async function restore({local = '3', localTime, remote = 10, remoteTime = 2000,
    bookmark = '', fail = false, storage = new Map(), slowInitial = false,
    navigateWhileLoading = false, nativeCfi} = {}) {
    const key = 'calibre.reader.progress./book.epub';
    const settingsKey = 'epubjsreader:test';
    if (!storage.has(key) && local !== null) storage.set(key, local);
    if (localTime !== undefined) storage.set(key + '.updatedAt', String(localTime));
    let queueCallback;
    let current = Number(local || 0) / 100;
    const previous = JSON.parse(storage.get(settingsKey) || '{}');
    if (nativeCfi !== undefined) previous.previousLocationCfi = nativeCfi;
    if (previous.previousLocationCfi) current = previous.previousLocationCfi;
    const displayed = [];
    const listeners = {};
    const renditionListeners = new Map();
    const timers = new Set();
    const progress = {textContent: ''};
    const config = {bookUrl:'/book.epub', kosyncPercent:remote,
        kosyncUpdatedAt: new Date(remoteTime).toISOString(), bookmark};
    let finishInitial;
    const initial = slowInitial ? new Promise(resolve => { finishInitial = resolve; }) : Promise.resolve();
    let generateLocations;
    const generated = navigateWhileLoading ? new Promise(resolve => { generateLocations = resolve; }) : Promise.resolve();
    const rendition = {
        q:{running:undefined}, location:{end:{cfi:'location'}},
        currentLocation() { return {start:{cfi:current}, end:{cfi: Number(current) + 0.01}}; },
        on(name, cb) {
            if (!renditionListeners.has(name)) renditionListeners.set(name, new Set());
            renditionListeners.get(name).add(cb);
        },
        off(name, cb) { renditionListeners.get(name)?.delete(cb); },
        async display(cfi) {
            if (fail) throw Error('display failed');
            displayed.push(cfi); current=cfi;
            // epub.js resolves display before emitting the relocated event.
            setImmediate(() => {
                listeners.locationchange?.();
                renditionListeners.get('relocated')?.forEach(cb => cb({start:{cfi:current}}));
            });
        },
    };
    const reader = {rendition, displayed:initial, settings:{bookKey:settingsKey, ...previous},
        saveSettings() {
            this.settings.previousLocationCfi = rendition.currentLocation().start.cfi;
            storage.set(settingsKey, JSON.stringify(this.settings));
        },
        book: {ready:Promise.resolve(), locations: {
            async generate() { await generated; },
            cfiFromPercentage(p) { return p === 0 ? 'start' : p; },
            percentageFromCfi(cfi) { return cfi === 'start' ? 0 : Number(cfi); },
        }},
    };
    const context = {
        console: {warn() {}}, Event: class { constructor(type) { this.type = type; } },
        calibre: config,
        window: {calibre:config, addEventListener(name, cb) { listeners[name] = cb; },
            dispatchEvent(event) { listeners[event.type]?.(); }},
        history: {pushState() {},replaceState() {}},
        localStorage: {getItem(k) { return storage.get(k) ?? null; }, setItem(k,v) { storage.set(k,String(v)); }},
        document: {getElementById() { return progress; }, addEventListener() {}},
        reader,
        ePub() { return reader.book; },
        setInterval(cb) { queueCallback=cb; return 1; },clearInterval() {},
        setTimeout(cb) { timers.add(cb); return cb; },clearTimeout(cb) { timers.delete(cb); },
    };
    vm.createContext(context);
    vm.runInContext(source,context);
    listeners.locationchange();
    assert.equal(storage.get(key) ?? null, local);
    queueCallback();
    if (slowInitial) {
        await new Promise(setImmediate);
        assert.deepEqual(displayed, [], 'Must wait for the initial cached display before restoring');
        finishInitial();
    }
    if (navigateWhileLoading) {
        await new Promise(setImmediate);
        current = 0.07;
        renditionListeners.get('keydown').forEach(cb => cb({key:'ArrowRight'}));
        listeners.locationchange();
        generateLocations();
    }
    for (let i = 0; i < 20 && !vm.runInContext('voicebookRestoreComplete', context); i++) {
        await new Promise(setImmediate);
    }
    assert.equal(vm.runInContext('voicebookRestoreComplete', context), true, 'Restoration must finish');
    assert.equal(timers.size, 0, 'Restore must release its relocation timeout');
    return {displayed,storage,key,settingsKey,progress,listeners,setPosition(p) { current=p; }};
}

(async () => {
    let result = await restore({localTime:1000});
    assert.deepEqual(result.displayed,[0.1]);
    assert.equal(result.storage.get(result.key),'10');
    assert.equal(result.storage.get(result.key+'.updatedAt'),'2000');
    assert.equal(result.storage.get(result.key+'.cfi'),'0.1');
    assert.equal(JSON.parse(result.storage.get(result.settingsKey)).previousLocationCfi, 0.1);
    assert.equal(result.progress.textContent,'10%');
    result.setPosition(0.1234); result.listeners.locationchange();
    assert.equal(result.storage.get(result.key),'12.34');
    assert.ok(Number(result.storage.get(result.key+'.updatedAt')) > 2000);
    // Reopen from the homepage with the same browser cache: preserve the exact anchor.
    result = await restore({storage:result.storage, local:'12.34'});
    assert.deepEqual(result.displayed,['0.1234']);
    assert.equal(JSON.parse(result.storage.get(result.settingsKey)).previousLocationCfi, '0.1234');
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
    result = await restore({slowInitial:true,localTime:1000});
    assert.deepEqual(result.displayed,[0.1]);
    // Restoring server progress does not fabricate a newer local timestamp.
    result = await restore({storage:result.storage,local:'10',remote:20,remoteTime:2001});
    assert.deepEqual(result.displayed,[0.2]);
    result = await restore({navigateWhileLoading:true});
    assert.deepEqual(result.displayed,[], 'Slow restore must not move an active reader');
    assert.ok(Math.abs(Number(result.storage.get(result.key)) - 7) < 1e-8);
    result = await restore({localTime:5000, local:'3', remote:44, remoteTime:2000});
    assert.deepEqual(result.displayed,[0.44], 'Migrate v1 cache whose initialization stamped an old position as new');
    result = await restore({local:null, nativeCfi:'0.27', remote:0});
    assert.deepEqual(result.displayed,['0.27'], 'Native browser position must not be reset by stale cloud zero');
    result = await restore({local:null, nativeCfi:'0.27', remote:null});
    assert.deepEqual(result.displayed,['0.27'], 'Native position alone is enough to restore browser-only reading');
    result = await restore({local:null, nativeCfi:'0.27', remote:44});
    assert.deepEqual(result.displayed,[0.44], 'A significant remote lead can still migrate a native-only position');
    result = await restore({local:'0', nativeCfi:'0.27', remote:10});
    assert.deepEqual(result.displayed,['0.27'], 'Stale percentage cache must not override a farther native exact position');
    console.log('CWA web restore: 16 scenarios passed');
})().catch(error => { console.error(error); process.exitCode=1; });

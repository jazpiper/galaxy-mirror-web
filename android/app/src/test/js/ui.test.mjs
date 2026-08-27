import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import test from 'node:test';

class FakeEventTarget {
    constructor(id = '') {
        this._id = id;
        this.listeners = new Map();
        this.children = [];
        const classes = new Set();
        this.classList = {
            add(...names) {
                names.forEach(name => classes.add(name));
            },
            remove(...names) {
                names.forEach(name => classes.delete(name));
            },
            contains(name) {
                return classes.has(name);
            },
            toggle(name, force) {
                if (force === true) {
                    classes.add(name);
                    return true;
                }
                if (force === false) {
                    classes.delete(name);
                    return false;
                }
                if (classes.has(name)) {
                    classes.delete(name);
                    return false;
                }
                classes.add(name);
                return true;
            }
        };
        this.style = {};
        this.textContent = '';
        this.innerText = '';
        this._innerHTML = '';
        this.scrollTop = 0;
        this.scrollHeight = 0;
        this.value = '';
        this.className = '';
        this.disabled = false;
        this.src = '';
        this.hidden = false;
    }

    get id() {
        return this._id;
    }

    set id(val) {
        this._id = val;
        if (this.ownerDocument) {
            this.ownerDocument.elements.set(val, this);
        }
    }

    getContext(type) {
        if (type === '2d') {
            return {
                clearRect: (x, y, w, h) => { this.cleared = { x, y, w, h }; },
                drawImage: (image, sx, sy, sw, sh, dx, dy, dw, dh) => {}
            };
        }
        return null;
    }

    get innerHTML() {
        return this._innerHTML;
    }

    set innerHTML(value) {
        this._innerHTML = value;
        this.textContent = String(value).replace(/<[^>]+>/g, '');
    }

    get textContent() {
        if (this.children.length > 0) {
            return this.children.map(c => c.textContent).join('');
        }
        return this._textContent || '';
    }

    set textContent(value) {
        this._textContent = value;
    }

    addEventListener(type, listener) {
        const listeners = this.listeners.get(type) || [];
        listeners.push(listener);
        this.listeners.set(type, listeners);
    }

    removeEventListener(type, listener) {
        if (this.listeners.has(type)) {
            const listeners = this.listeners.get(type).filter(l => l !== listener);
            this.listeners.set(type, listeners);
        }
    }

    dispatchEvent(event) {
        event.target = event.target || this;
        for (const listener of this.listeners.get(event.type) || []) {
            listener(event);
        }
    }

    appendChild(child) {
        this.children.push(child);
        child.parentNode = this;
    }

    insertBefore(newChild, referenceChild) {
        const index = this.children.indexOf(referenceChild);
        if (index !== -1) {
            this.children.splice(index, 0, newChild);
        } else {
            this.children.push(newChild);
        }
        newChild.parentNode = this;
    }

    removeChild(child) {
        this.children = this.children.filter(item => item !== child);
        child.parentNode = null;
    }

    replaceChildren(...children) {
        this.children = [...children];
        for (const child of this.children) {
            child.parentNode = this;
        }
    }

    remove() {
        this.parentNode?.removeChild(this);
    }

    focus() {
        if (this.ownerDocument) {
            this.ownerDocument.activeElement = this;
        }
    }

    select() {}

    setAttribute(name, value) {
        this[name] = value;
    }

    removeAttribute(name) {
        delete this[name];
    }

    setSelectionRange() {}

    querySelector() {
        return null;
    }

    getBoundingClientRect() {
        return { left: 0, top: 0, width: 360, height: 800 };
    }

    get childElementCount() {
        return this.children.length;
    }

    get firstChild() {
        return this.children[0] || null;
    }

    load() {
        this.loaded = true;
    }
}

class FakeDocument extends FakeEventTarget {
    constructor() {
        super('document');
        this.elements = new Map();
        this.activeElement = null;
        this.body = new FakeEventTarget('body');
        this.body.ownerDocument = this;
    }

    getElementById(id) {
        if (!this.elements.has(id)) {
            const element = new FakeEventTarget(id);
            element.ownerDocument = this;
            this.elements.set(id, element);
        }
        return this.elements.get(id);
    }

    createElement(tagName) {
        const element = new FakeEventTarget(tagName);
        element.ownerDocument = this;
        return element;
    }

    createDocumentFragment() {
        const fragment = new FakeEventTarget('fragment');
        fragment.ownerDocument = this;
        return fragment;
    }
}

class FakeClock {
    constructor() {
        this.nextId = 1;
        this.tasks = [];
    }

    setTimeout(callback, delay = 0) {
        const id = this.nextId++;
        this.tasks.push({ id, callback, delay, remaining: delay });
        return id;
    }

    clearTimeout(id) {
        this.tasks = this.tasks.filter(t => t.id !== id);
    }

    setInterval(callback, delay = 0) {
        return this.setTimeout(callback, delay);
    }

    clearInterval(id) {
        this.clearTimeout(id);
    }

    tick(ms) {
        let remaining = ms;
        while (true) {
            this.tasks.sort((a, b) => a.remaining - b.remaining);
            const next = this.tasks[0];
            if (!next || next.remaining > remaining) break;

            const elapsed = next.remaining;
            for (const task of this.tasks) {
                task.remaining -= elapsed;
            }
            remaining -= elapsed;

            const due = this.tasks.filter(task => task.remaining <= 0);
            this.tasks = this.tasks.filter(task => task.remaining > 0);
            for (const task of due) {
                task.callback();
            }
        }

        for (const task of this.tasks) {
            task.remaining -= remaining;
        }
    }

    runAll() {
        this.tick(100);
    }
}

function loadUiContext(options = {}) {
    const appRoot = path.resolve(import.meta.dirname, '../../..');
    const filesDir = path.join(appRoot, 'src/main/resources/files');
    const contextDocument = new FakeDocument();
    const clock = new FakeClock();
    const viewerUrl = new URL(options.url || 'http://example.test:8080/');
    const fetchCalls = [];
    const objectUrls = [];
    const revokedObjectUrls = [];

    function FakeURL(url, base) {
        return new URL(url, base);
    }
    FakeURL.createObjectURL = (blob) => {
        const url = `blob:fake-${objectUrls.length + 1}`;
        objectUrls.push({ url, blob });
        return url;
    };
    FakeURL.revokeObjectURL = (url) => {
        revokedObjectUrls.push(url);
    };

    const context = {
        console,
        document: contextDocument,
        window: {
            location: {
                protocol: viewerUrl.protocol,
                host: viewerUrl.host,
                hostname: viewerUrl.hostname,
                search: viewerUrl.search,
                href: viewerUrl.href,
                pathname: viewerUrl.pathname
            },
            setTimeout: (callback, delay) => clock.setTimeout(callback, delay),
            clearTimeout: (id) => clock.clearTimeout(id),
            setInterval: (callback, delay) => clock.setInterval(callback, delay),
            clearInterval: (id) => clock.clearInterval(id),
            requestAnimationFrame: (callback) => clock.setTimeout(callback, 16),
            cancelAnimationFrame: (id) => clock.clearTimeout(id)
        },
        navigator: options.navigator || {},
        Date,
        JSON,
        URL: FakeURL,
        URLSearchParams,
        parseFloat,
        Math,
        Number,
        Boolean,
        Array,
        String,
        setTimeout: (callback, delay) => clock.setTimeout(callback, delay),
        clearTimeout: (id) => clock.clearTimeout(id),
        setInterval: (callback, delay) => clock.setInterval(callback, delay),
        clearInterval: (id) => clock.clearInterval(id),
        requestAnimationFrame: (callback) => clock.setTimeout(callback, 16),
        cancelAnimationFrame: (id) => clock.clearTimeout(id),
        fetch: async (url, options = {}) => {
            fetchCalls.push({ url, options });
            return {
                ok: true,
                json: async () => ({ apps: [] }),
                text: async () => ''
            };
        }
    };
    context.window.document = contextDocument;
    context.window.fetch = context.fetch;
    vm.createContext(context);

    function loadModuleFile(filename) {
        const filePath = path.join(filesDir, filename);
        if (!fs.existsSync(filePath)) return;
        let code = fs.readFileSync(filePath, 'utf8');
        code = code.replace(/^import\s*\{[\s\S]*?\}\s*from\s*['"][^'"]+['"];?/gm, '');
        code = code.replace(/^import\s+[\s\S]*?from\s+['"][^'"]+['"];?/gm, '');
        code = code.replace(/^export\s+default\s+/gm, '');
        code = code.replace(/^export\s*\{[\s\S]*?\};?/gm, '');
        code = code.replace(/^export\s+/gm, '');
        vm.runInContext(code, context, { filename });
    }

    ['webrtc.js', 'controls.js', 'signaling.js', 'ui.js'].forEach(loadModuleFile);

    return {
        context,
        document: contextDocument,
        clock,
        fetchCalls,
        objectUrls,
        revokedObjectUrls
    };
}

test('formatting utilities (formatMegabytes, formatBitrate, formatBytesPerSecond)', () => {
    const { context } = loadUiContext();

    assert.equal(vm.runInContext('formatMegabytes(1048576)', context), '1.00 MB');
    assert.equal(vm.runInContext('formatMegabytes(5242880)', context), '5.00 MB');

    assert.equal(vm.runInContext('formatBitrate(3000000)', context), '3.0Mbps');
    assert.equal(vm.runInContext('formatBitrate(NaN)', context), '');
    assert.equal(vm.runInContext('formatBitrate("invalid")', context), '');

    assert.equal(vm.runInContext('formatBytesPerSecond(1500000)', context), '1.5 MB/s');
    assert.equal(vm.runInContext('formatBytesPerSecond(0)', context), '0.0 MB/s');
    assert.equal(vm.runInContext('formatBytesPerSecond(-100)', context), '0.0 MB/s');
    assert.equal(vm.runInContext('formatBytesPerSecond(null)', context), '0.0 MB/s');
});

test('setHidden helper toggles hidden property', () => {
    const { context, document } = loadUiContext();

    const element = document.createElement('div');
    context.testElement = element;
    vm.runInContext('setHidden(testElement, true);', context);
    assert.equal(element.hidden, true);

    vm.runInContext('setHidden(testElement, false);', context);
    assert.equal(element.hidden, false);

    assert.doesNotThrow(() => {
        vm.runInContext('setHidden(null, true);', context);
    });
});

test('showConnectionPlaceholder and hideConnectionPlaceholder', () => {
    const { context, document } = loadUiContext();

    vm.runInContext('showConnectionPlaceholder("Testing message");', context);
    const placeholder = document.getElementById('connectionPlaceholder');
    assert.equal(placeholder.textContent, 'Testing message');
    assert.equal(placeholder.classList.contains('hidden'), false);

    vm.runInContext('hideConnectionPlaceholder();', context);
    assert.equal(placeholder.classList.contains('hidden'), true);
});

test('updateBlackOverlayStatus updates active class and title', () => {
    const { context, document } = loadUiContext();

    vm.runInContext('updateBlackOverlayStatus(true);', context);
    const btn = document.getElementById('btn-black-overlay');
    assert.equal(vm.runInContext('isBlackOverlayActive', context), true);
    assert.equal(btn.classList.contains('active'), true);
    assert.match(btn.title, /차단 ON/);

    vm.runInContext('updateBlackOverlayStatus(false);', context);
    assert.equal(vm.runInContext('isBlackOverlayActive', context), false);
    assert.equal(btn.classList.contains('active'), false);
    assert.match(btn.title, /차단 OFF/);
});

test('updateAutoFitStatus updates active class and title', () => {
    const { context, document } = loadUiContext();

    vm.runInContext('updateAutoFitStatus(true);', context);
    const btn = document.getElementById('btn-auto-fit');
    assert.equal(vm.runInContext('isAutoFitActive', context), true);
    assert.equal(btn.classList.contains('active'), true);
    assert.match(btn.title, /창 맞춤 미러링 ON/);

    vm.runInContext('updateAutoFitStatus(false);', context);
    assert.equal(vm.runInContext('isAutoFitActive', context), false);
    assert.equal(btn.classList.contains('active'), false);
    assert.match(btn.title, /창 맞춤 미러링 OFF/);
});

test('showStatusDetail sets class name and text content', () => {
    const { context, document } = loadUiContext();

    vm.runInContext('showStatusDetail("Connected successfully", "success");', context);
    const detail = document.getElementById('statusDetail');
    assert.equal(detail.className, 'status-detail success');
    assert.equal(detail.textContent, 'Connected successfully');

    vm.runInContext('showStatusDetail("Connecting...", "");', context);
    assert.equal(detail.className, 'status-detail');
    assert.equal(detail.textContent, 'Connecting...');
});

test('renderFavoriteApps renders shortcuts or empty message', () => {
    const { context, document } = loadUiContext();
    const list = document.getElementById('favoriteAppsList');

    vm.runInContext('renderFavoriteApps([]);', context);
    assert.equal(list.children.length, 1);
    assert.equal(list.children[0].className, 'shortcut-empty');

    vm.runInContext('renderFavoriteApps(null);', context);
    assert.equal(list.children.length, 1);
    assert.equal(list.children[0].className, 'shortcut-empty');

    vm.runInContext('renderFavoriteApps([{ packageName: "com.test.app", label: "Test App" }]);', context);
    assert.equal(list.children.length, 1);
    assert.equal(list.children[0].className, 'shortcut-btn');
    assert.equal(list.children[0].textContent, 'Test App');

    vm.runInContext('renderFavoriteApps([{ packageName: "com.test.nolabel" }]);', context);
    assert.equal(list.children[0].textContent, 'com.test.nolabel');
});

test('addClipboardToHistory and renderClipboardHistory', () => {
    const { context, document } = loadUiContext();

    vm.runInContext('addClipboardToHistory("item 1");', context);
    let history = Array.from(vm.runInContext('clipboardHistory', context));
    assert.deepEqual(history, ['item 1']);

    // Ignore duplicate immediate repeat
    vm.runInContext('addClipboardToHistory("item 1");', context);
    history = Array.from(vm.runInContext('clipboardHistory', context));
    assert.deepEqual(history, ['item 1']);

    // Ignore empty/whitespace
    vm.runInContext('addClipboardToHistory("   ");', context);
    history = Array.from(vm.runInContext('clipboardHistory', context));
    assert.deepEqual(history, ['item 1']);

    vm.runInContext('addClipboardToHistory("item 2");', context);
    history = Array.from(vm.runInContext('clipboardHistory', context));
    assert.deepEqual(history, ['item 2', 'item 1']);

    // Test max limit capping (30)
    for (let i = 3; i <= 35; i++) {
        vm.runInContext(`addClipboardToHistory("item ${i}");`, context);
    }
    history = Array.from(vm.runInContext('clipboardHistory', context));
    assert.equal(history.length, 30);
    assert.equal(history[0], 'item 35');

    // Test clear clipboard btn handler
    vm.runInContext('handleClearClipboardBtnClick();', context);
    history = Array.from(vm.runInContext('clipboardHistory', context));
    assert.equal(history.length, 0);

    const listContainer = document.getElementById('clipboardHistoryList');
    assert.match(listContainer.innerHTML, /수신된 클립보드 내역이 없습니다/);
});

test('renderStreamQualityStatus updates UI element text and button active state', () => {
    const { context, document } = loadUiContext();

    vm.runInContext('renderStreamQualityStatus({ selectedMode: "HIGH", effectiveMode: "HIGH", networkTransport: "WIFI", width: 1080, height: 2400, fps: 30, maxBitrateBps: 6000000 });', context);

    assert.equal(document.getElementById('qualityMode').textContent, 'HIGH');
    assert.match(document.getElementById('qualityEffective').textContent, /HIGH · 활성 · 1080x2400 30fps · 6\.0Mbps/);
    assert.equal(document.getElementById('qualityNetwork').textContent, 'WIFI');

    const highBtn = document.getElementById('qualityHighBtn');
    const standardBtn = document.getElementById('qualityStandardBtn');
    assert.equal(highBtn.classList.contains('active'), true);
    assert.equal(standardBtn.classList.contains('active'), false);
});

test('renderUsbCoolingStatus and hideUsbCoolingStatus', () => {
    const { context, document } = loadUiContext();

    vm.runInContext('renderUsbCoolingStatus({ effectiveTier: "BALANCED", width: 720, height: 1600, fps: 30, codec: "h264", maxBitrateBps: 4000000 }, { thermalStatus: "LIGHT", bytesPerSecond: 500000 });', context);
    const item = document.getElementById('usbCoolingStatusItem');
    const status = document.getElementById('usbCoolingStatus');
    assert.equal(item.hidden, false);
    assert.match(status.textContent, /USB BALANCED/);
    assert.match(status.textContent, /H\.264/);
    assert.match(status.textContent, /720x1600/);
    assert.match(status.textContent, /30fps/);
    assert.match(status.textContent, /4\.0Mbps/);
    assert.match(status.textContent, /thermal LIGHT/);

    vm.runInContext('hideUsbCoolingStatus();', context);
    assert.equal(item.hidden, true);
});

test('clearRemoteVideoFrame and clearUsbFrame', () => {
    const { context, document, revokedObjectUrls } = loadUiContext();

    const remoteVideo = document.getElementById('remoteVideo');
    remoteVideo.srcObject = { active: true };
    vm.runInContext('clearRemoteVideoFrame();', context);
    assert.equal(remoteVideo.srcObject, null);
    assert.equal(remoteVideo.loaded, true);

    const usbCanvas = document.getElementById('usbCanvas');
    usbCanvas.width = 100;
    usbCanvas.height = 100;
    vm.runInContext('lastUsbFrameUrl = "blob:fake-1"; clearUsbFrame();', context);
    assert.deepEqual(revokedObjectUrls, ['blob:fake-1']);
    assert.equal(vm.runInContext('lastUsbFrameUrl', context), null);
});

test('showGlowToast creates toast notification element', () => {
    const { context, document, clock } = loadUiContext();

    const toast = vm.runInContext('showGlowToast("Operation complete");', context);
    const container = document.getElementById('toastContainer');
    assert.equal(container.children.length, 1);
    assert.equal(toast.textContent, '🔔Operation complete');

    clock.tick(3500);
    assert.equal(container.children.length, 0);
});

test('log and flushLogs queue messages and cap child elements to 200', () => {
    const { context, document, clock } = loadUiContext();

    vm.runInContext('log("First log message");', context);
    assert.equal(vm.runInContext('logQueue.length', context), 1);

    clock.runAll();
    const logBox = document.getElementById('logBox');
    assert.equal(logBox.children.length, 1);
    assert.match(logBox.children[0].textContent, /First log message/);
    assert.equal(vm.runInContext('logQueue.length', context), 0);

    // Test capping at 200 elements
    for (let i = 0; i < 220; i++) {
        vm.runInContext(`log("Log ${i}");`, context);
        clock.runAll();
    }
    assert.equal(logBox.children.length, 200);
});

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

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
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) {
            this.listeners.set(type, []);
        }
        this.listeners.get(type).push(listener);
    }

    removeEventListener(type, listener) {
        if (!this.listeners.has(type)) return;
        const index = this.listeners.get(type).indexOf(listener);
        if (index >= 0) {
            this.listeners.get(type).splice(index, 1);
        }
    }

    dispatchEvent(event) {
        const type = typeof event === 'string' ? event : event.type;
        const listeners = this.listeners.get(type) || [];
        listeners.forEach(listener => listener(event));
        return !event?.defaultPrevented;
    }

    appendChild(child) {
        this.children.push(child);
        child.parentElement = this;
        child.parentNode = this;
        return child;
    }

    removeChild(child) {
        const index = this.children.indexOf(child);
        if (index >= 0) {
            this.children.splice(index, 1);
            child.parentElement = null;
            child.parentNode = null;
        }
        return child;
    }

    replaceChildren(...children) {
        this.children = [...children];
        for (const child of this.children) {
            child.parentElement = this;
            child.parentNode = this;
        }
    }

    remove() {
        if (this.parentElement) {
            this.parentElement.removeChild(this);
        }
    }

    getContext(type) {
        if (type === '2d') {
            return {
                clearRect(x, y, w, h) {},
                drawImage(image, sx, sy, sw, sh, dx, dy, dw, dh) {}
            };
        }
        return null;
    }

    focus() {}
    select() {}
    setAttribute() {}
    getBoundingClientRect() {
        return {
            left: 0,
            top: 0,
            width: 1080,
            height: 2400
        };
    }
}

class FakeDocument extends FakeEventTarget {
    constructor() {
        super('document');
        this.elements = new Map();
        this.body = new FakeEventTarget('body');
        const elementsToCreate = [
            'remoteVideo', 'keyboardSink', 'connectBtn', 'wsIndicator', 'wsStatus',
            'rtcStatus', 'streamStatusLabel', 'rtcLatencyItem', 'controlStatus',
            'accessibilityStatus', 'favoriteAppsList', 'statusDetail', 'logBox',
            'uploadUsage', 'downloadUsage', 'rtcLatency', 'usbCanvas',
            'connectionPlaceholder', 'transportTailscaleBtn', 'transportUsbBtn',
            'qualityMode', 'qualityEffective', 'qualityNetwork', 'qualityNetworkItem',
            'usbCoolingStatusItem', 'usbCoolingStatus', 'toolsPanel', 'qualityAutoBtn',
            'qualityDataSaverBtn', 'qualityStandardBtn', 'qualityHighBtn',
            'navRecentsBtn', 'navHomeBtn', 'navBackBtn', 'clipboardHistory',
            'clearClipboardBtn', 'btn-black-overlay', 'btn-auto-fit',
            'videoContainer', 'powerBtn', 'toastContainer', 'usbFrame'
        ];
        elementsToCreate.forEach(id => {
            const elem = new FakeEventTarget(id);
            elem.id = id;
            elem.ownerDocument = this;
            this.elements.set(id, elem);
        });
    }

    getElementById(id) {
        if (!this.elements.has(id)) {
            const elem = new FakeEventTarget(id);
            elem.id = id;
            elem.ownerDocument = this;
            this.elements.set(id, elem);
        }
        return this.elements.get(id);
    }

    createElement(tagName) {
        const elem = new FakeEventTarget(tagName);
        elem.tagName = tagName;
        elem.ownerDocument = this;
        return elem;
    }

    createDocumentFragment() {
        const fragment = new FakeEventTarget('fragment');
        fragment.ownerDocument = this;
        return fragment;
    }

    execCommand() {
        return true;
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
}

function loadViewer(options = {}) {
    const appRoot = path.resolve(import.meta.dirname, '../../..');
    const filesDir = path.join(appRoot, 'src/main/resources/files');
    const contextDocument = new FakeDocument();
    const clock = new FakeClock();
    const viewerUrl = new URL(options.url || 'http://example.test:8080/');
    const fetchCalls = [];
    const webSockets = [];

    class FakeWebSocket {
        static CONNECTING = 0;
        static OPEN = 1;
        static CLOSING = 2;
        static CLOSED = 3;

        constructor(url) {
            this.url = url;
            this.readyState = FakeWebSocket.OPEN;
            this.sent = [];
            this.sentMessages = [];
            webSockets.push(this);
        }

        send(payload) {
            this.sentMessages.push(payload);
            this.sent.push(JSON.parse(payload));
        }

        close(code = 1000, reason = '') {
            this.readyState = FakeWebSocket.CLOSED;
            this.onclose?.({ code, reason });
        }
    }

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
        WebSocket: FakeWebSocket,
        navigator: options.navigator || {},
        Date,
        JSON,
        URLSearchParams,
        parseFloat,
        Math,
        setTimeout: (callback, delay) => clock.setTimeout(callback, delay),
        clearTimeout: (id) => clock.clearTimeout(id),
        setInterval: (callback, delay) => clock.setInterval(callback, delay),
        clearInterval: (id) => clock.clearInterval(id),
        requestAnimationFrame: (callback) => clock.setTimeout(callback, 16),
        cancelAnimationFrame: (id) => clock.clearTimeout(id),
        fetch: async (url, opts = {}) => {
            fetchCalls.push({ url, options: opts });
            return {
                ok: true,
                json: async () => ({ apps: [] }),
                text: async () => ''
            };
        }
    };
    context.window.document = contextDocument;
    context.window.fetch = context.fetch;
    context.window.navigator = context.navigator;
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

    const keyboardHelperPath = path.join(filesDir, 'viewer-keyboard.js');
    if (fs.existsSync(keyboardHelperPath)) {
        vm.runInContext(fs.readFileSync(keyboardHelperPath, 'utf8'), context, {
            filename: keyboardHelperPath
        });
    }

    ['webrtc.js', 'controls.js', 'signaling.js', 'ui.js', 'main.js'].forEach(loadModuleFile);

    const messages = [];
    context.channel = {
        readyState: 'open',
        send(payload) {
            messages.push(JSON.parse(payload));
        }
    };
    vm.runInContext('dataChannel = channel; setupDataChannelHandlers(channel); keyControlInitialized = false; setupKeyControl();', context);

    return {
        context,
        document: contextDocument,
        messages,
        clock,
        webSockets,
        fetchCalls
    };
}

async function test(name, fn) {
    try {
        await fn();
        console.log(`ok - ${name}`);
    } catch (error) {
        console.error(`not ok - ${name}`);
        throw error;
    }
}

await test('sendControlPayload & sendAndroidKey send messages via DataChannel and USB socket', () => {
    const { context, messages, webSockets } = loadViewer();

    // 1. WebRTC DataChannel transport
    const successKey = vm.runInContext('sendControlPayload({ type: "key", keyCode: 26 });', context);
    assert.equal(successKey, true);
    assert.equal(messages[messages.length - 1].type, 'key');
    assert.equal(messages[messages.length - 1].keyCode, 26);

    vm.runInContext('sendAndroidKey(3);', context);
    assert.equal(messages[messages.length - 1].type, 'key');
    assert.equal(messages[messages.length - 1].keyCode, 3);

    // WebRTC DataChannel closed
    vm.runInContext('dataChannel.readyState = "closed";', context);
    const failKey = vm.runInContext('sendControlPayload({ type: "key", keyCode: 4 });', context);
    assert.equal(failKey, false);

    // 2. USB Transport
    vm.runInContext('selectedTransport = "usb"; connectMirror();', context);
    const usbSock = webSockets[0];
    usbSock.onopen();

    const usbSent = vm.runInContext('sendControlPayload({ type: "key", keyCode: 187 });', context);
    assert.equal(usbSent, true);
    assert.equal(usbSock.sent[usbSock.sent.length - 1].type, 'key');
    assert.equal(usbSock.sent[usbSock.sent.length - 1].keyCode, 187);

    usbSock.readyState = 3; // CLOSED
    const usbFail = vm.runInContext('sendControlPayload({ type: "key", keyCode: 187 });', context);
    assert.equal(usbFail, false);
});

await test('sendSequencedTextPayload, handleControlAck, timeouts, and state reset', () => {
    const { context, messages, clock } = loadViewer();

    vm.runInContext('resetTextControlState();', context);
    assert.equal(vm.runInContext('nextTextSeq', context), 1);
    assert.equal(vm.runInContext('inFlightTextSeq', context), null);

    // Send payload 1
    const res1 = vm.runInContext('sendSequencedTextPayload({ type: "text", action: "commit", text: "hello" });', context);
    assert.equal(res1, true);
    assert.equal(vm.runInContext('inFlightTextSeq', context), 1);
    assert.equal(messages[0].type, 'text');
    assert.equal(messages[0].action, 'commit');
    assert.equal(messages[0].text, 'hello');
    assert.equal(messages[0].seq, 1);

    // Second payload gets queued because payload 1 is in-flight
    const res2 = vm.runInContext('sendSequencedTextPayload({ type: "text", action: "commit", text: "world" });', context);
    assert.equal(res2, false);
    assert.equal(vm.runInContext('queuedTextPayloads.length', context), 1);

    // Handle ACK for payload 1 -> flushes queued payload 2 with seq 2
    vm.runInContext('handleControlAck({ seq: 1, applied: true });', context);
    assert.equal(vm.runInContext('inFlightTextSeq', context), 2);
    assert.equal(vm.runInContext('queuedTextPayloads.length', context), 0);
    assert.equal(messages[1].type, 'text');
    assert.equal(messages[1].action, 'commit');
    assert.equal(messages[1].text, 'world');
    assert.equal(messages[1].seq, 2);

    // Test ACK timeout
    clock.tick(1500);
    assert.equal(vm.runInContext('inFlightTextSeq', context), null);

    // Test resetTextControlState
    vm.runInContext('sendSequencedTextPayload({ type: "text", action: "commit", text: "test" });', context);
    vm.runInContext('resetTextControlState();', context);
    assert.equal(vm.runInContext('inFlightTextSeq', context), null);
    assert.equal(vm.runInContext('queuedTextPayloads.length', context), 0);
    assert.equal(vm.runInContext('nextTextSeq', context), 1);
});

await test('getNormalizedCoords calculates letterboxing accurately', () => {
    const { context, document } = loadViewer();
    const video = document.getElementById('remoteVideo');
    video.videoWidth = 1080;
    video.videoHeight = 2400; // ratio = 1080 / 2400 = 0.45

    // Case 1: element is wider than video ratio (rElem > rVideo)
    // wElem = 1200, hElem = 2000 -> rElem = 0.6 > 0.45
    // wAct = 2000 * 0.45 = 900. wMargin = (1200 - 900) / 2 = 150.
    video.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1200, height: 2000 });

    const coords1 = vm.runInContext('getNormalizedCoords({ clientX: 600, clientY: 1000 }, remoteVideo);', context);
    assert.equal(coords1.x, 0.5);
    assert.equal(coords1.y, 0.5);

    // Out of bounds on left margin (clientX = 50 < 150 margin)
    const coordsOob = vm.runInContext('getNormalizedCoords({ clientX: 50, clientY: 1000 }, remoteVideo);', context);
    assert.equal(coordsOob, null);

    // Case 2: element is taller than video ratio (rElem < rVideo)
    // wElem = 1000, hElem = 3000 -> rElem = 0.333 < 0.45
    // hAct = 1000 / 0.45 = 2222.22. hMargin = (3000 - 2222.22) / 2 = 388.88
    video.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1000, height: 3000 });
    const coords2 = vm.runInContext('getNormalizedCoords({ clientX: 500, clientY: 1500 }, remoteVideo);', context);
    assert.equal(coords2.x, 0.5);
    assert.ok(Math.abs(coords2.y - 0.5) < 0.01);
});

await test('setupTouchControl, mouse drag swipe, tap, and wheel swipe handling', () => {
    const { context, document, messages, clock } = loadViewer();
    const surface = document.getElementById('remoteVideo');
    surface.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1000, height: 2000 });
    surface.videoWidth = 1000;
    surface.videoHeight = 2000;

    vm.runInContext('destroyTouchControl(); setupTouchControl();', context);

    // 1. Mouse Tap
    surface.dispatchEvent({ type: 'mousedown', clientX: 500, clientY: 1000, preventDefault() {} });
    surface.dispatchEvent({ type: 'mouseup', clientX: 500, clientY: 1000, preventDefault() {} });
    assert.equal(messages[messages.length - 1].type, 'tap');
    assert.equal(messages[messages.length - 1].x, 0.5);
    assert.equal(messages[messages.length - 1].y, 0.5);

    // 2. Mouse Drag Swipe (> DRAG_THRESHOLD_PX)
    surface.dispatchEvent({ type: 'mousedown', clientX: 500, clientY: 1000, preventDefault() {} });
    surface.dispatchEvent({ type: 'mousemove', buttons: 1, clientX: 500, clientY: 500, preventDefault() {} });
    surface.dispatchEvent({ type: 'mouseup', clientX: 500, clientY: 500, preventDefault() {} });
    const lastMsg = messages[messages.length - 1];
    assert.equal(lastMsg.type, 'swipe');
    assert.equal(lastMsg.x1, 0.5);
    assert.equal(lastMsg.y1, 0.5);
    assert.equal(lastMsg.x2, 0.5);
    assert.equal(lastMsg.y2, 0.25);

    // 3. Wheel Swipe
    surface.dispatchEvent({ type: 'wheel', deltaX: 0, deltaY: 200, clientX: 500, clientY: 1000, preventDefault() {} });
    clock.tick(50);
    const wheelMsg = messages[messages.length - 1];
    assert.equal(wheelMsg.type, 'swipe');
    assert.equal(wheelMsg.x1, 0.5);
    assert.equal(wheelMsg.x2, 0.5);

    // Clean up
    vm.runInContext('destroyTouchControl();', context);
    assert.equal(vm.runInContext('touchControlInitialized', context), false);
});

await test('clipboard helper APIs and manual fallback behavior', async () => {
    // 1. Without navigator.clipboard
    const { context: contextNoClip, document: docNoClip } = loadViewer({ navigator: {} });
    assert.equal(vm.runInContext('hasClipboardWriteApi()', contextNoClip), false);
    assert.equal(vm.runInContext('hasClipboardReadApi()', contextNoClip), false);

    vm.runInContext('showManualClipboardFallback("test-text");', contextNoClip);
    const toast = docNoClip.getElementById('toastContainer').children[0];
    const textSpan = toast.children[1];
    assert.match(textSpan.textContent, /클립보드 수신/);
    toast.onclick(); // Trigger manual copy execution
    const toast2 = docNoClip.getElementById('toastContainer').children[1];
    assert.match(toast2.children[1].textContent, /복사 완료/);

    // 2. With navigator.clipboard
    let writtenText = null;
    const { context: contextClip, document: docClip } = loadViewer({
        navigator: {
            clipboard: {
                writeText: async (t) => { writtenText = t; },
                readText: async () => 'clip-content'
            }
        }
    });
    assert.equal(vm.runInContext('hasClipboardWriteApi()', contextClip), true);
    assert.equal(vm.runInContext('hasClipboardReadApi()', contextClip), true);

    await vm.runInContext('writeClipboardFromAndroid("sync-text");', contextClip);
    assert.equal(writtenText, 'sync-text');
    assert.match(docClip.getElementById('toastContainer').children[0].children[1].textContent, /갤럭시 클립보드와 동기화되었습니다/);
});

await test('system, navigation, and display control toggles', () => {
    const { context, document, messages } = loadViewer();

    // 1. Black Overlay Toggle
    vm.runInContext('sendBlackOverlayToggle(true);', context);
    assert.equal(messages[messages.length - 1].type, 'black_overlay');
    assert.equal(messages[messages.length - 1].payload.enabled, true);

    // 2. Auto Fit Display & Mode Toggle
    vm.runInContext('toggleAutoFitMode(true);', context);
    const videoContainer = document.getElementById('videoContainer');
    assert.equal(videoContainer.style.aspectRatio, 'unset');
    assert.equal(videoContainer.style.width, '100%');
    assert.equal(messages[messages.length - 1].type, 'resize_display');
    assert.equal(messages[messages.length - 1].payload.width, 1080);
    assert.equal(messages[messages.length - 1].payload.height, 2400);

    vm.runInContext('toggleAutoFitMode(false);', context);
    assert.equal(videoContainer.style.aspectRatio, '9 / 19.5');

    // 3. System & Navigation Controls binding
    vm.runInContext('setupSystemControls(); setupNavigationControls();', context);

    document.getElementById('powerBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    assert.equal(messages[messages.length - 1].type, 'key');
    assert.equal(messages[messages.length - 1].keyCode, 26);

    document.getElementById('navBackBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    assert.equal(messages[messages.length - 1].type, 'key');
    assert.equal(messages[messages.length - 1].keyCode, 4);

    document.getElementById('navHomeBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    assert.equal(messages[messages.length - 1].type, 'key');
    assert.equal(messages[messages.length - 1].keyCode, 3);

    document.getElementById('navRecentsBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    assert.equal(messages[messages.length - 1].type, 'key');
    assert.equal(messages[messages.length - 1].keyCode, 187);
});

await test('keyboard control interceptor and lifecycle destroy KeyControl', () => {
    const { context } = loadViewer();

    vm.runInContext('setupKeyControl();', context);
    assert.equal(vm.runInContext('keyControlInitialized', context), true);

    vm.runInContext('destroyKeyControl();', context);
    assert.equal(vm.runInContext('keyControlInitialized', context), false);
    assert.equal(vm.runInContext('documentKeydownHandler', context), null);
});

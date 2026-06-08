import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

class FakeEventTarget {
    constructor(id = '') {
        this.id = id;
        this.listeners = new Map();
        this.children = [];
        this.classList = {
            add() {},
            remove() {}
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
    }

    get innerHTML() {
        return this._innerHTML;
    }

    set innerHTML(value) {
        this._innerHTML = value;
        this.textContent = String(value).replace(/<[^>]+>/g, '');
    }

    addEventListener(type, listener) {
        const listeners = this.listeners.get(type) || [];
        listeners.push(listener);
        this.listeners.set(type, listeners);
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
        // Advance time by 100ms to allow 35ms input buffering timers to execute,
        // but prevent the 1500ms watchdog timer from executing prematurely.
        this.tick(100);
    }
}

function keyboardEvent(key, extra = {}) {
    return {
        type: 'keydown',
        key,
        metaKey: false,
        ctrlKey: false,
        altKey: false,
        isComposing: false,
        defaultPrevented: false,
        preventDefault() {
            this.defaultPrevented = true;
        },
        ...extra
    };
}

function textInputEvent(type, data, extra = {}) {
    return {
        type,
        data,
        inputType: 'insertText',
        isComposing: false,
        defaultPrevented: false,
        preventDefault() {
            this.defaultPrevented = true;
        },
        ...extra
    };
}

function loadViewer(options = {}) {
    const appRoot = path.resolve(import.meta.dirname, '../../..');
    const filesDir = path.join(appRoot, 'src/main/resources/files');
    const contextDocument = new FakeDocument();
    const clock = new FakeClock();
    const fetchCalls = [];
    const webSockets = [];
    const peerConnections = [];
    class FakeWebSocket {
        static CONNECTING = 0;
        static OPEN = 1;
        static CLOSING = 2;
        static CLOSED = 3;

        constructor(url) {
            this.url = url;
            this.readyState = FakeWebSocket.OPEN;
            this.sent = [];
            webSockets.push(this);
        }

        send(payload) {
            this.sent.push(JSON.parse(payload));
        }

        close(code = 1000, reason = '') {
            this.readyState = FakeWebSocket.CLOSED;
            this.onclose?.({ code, reason });
        }
    }
    class FakeRTCPeerConnection {
        constructor() {
            this.closed = false;
            this.remoteDescription = null;
            peerConnections.push(this);
        }

        addTransceiver() {}

        createDataChannel(label, options) {
            this.dataChannel = {
                label,
                options,
                readyState: 'open',
                sent: [],
                send(payload) {
                    this.sent.push(JSON.parse(payload));
                },
                close() {
                    this.readyState = 'closed';
                    this.onclose?.();
                }
            };
            return this.dataChannel;
        }

        async createOffer() {
            return { type: 'offer', sdp: 'fake-offer' };
        }

        async setLocalDescription(description) {
            this.localDescription = description;
        }

        async addIceCandidate(candidate) {
            this.candidate = candidate;
        }

        async getStats() {
            return new Map();
        }

        close() {
            this.closed = true;
        }
    }
    const context = {
        console,
        document: contextDocument,
        window: {
            location: { protocol: 'http:', host: 'example.test:8080' },
            setTimeout: (callback, delay) => clock.setTimeout(callback, delay),
            clearTimeout: (id) => clock.clearTimeout(id),
            setInterval: (callback, delay) => clock.setInterval(callback, delay),
            clearInterval: (id) => clock.clearInterval(id)
        },
        WebSocket: FakeWebSocket,
        RTCPeerConnection: FakeRTCPeerConnection,
        RTCSessionDescription: class {},
        RTCIceCandidate: class {},
        navigator: options.navigator || {},
        MediaRecorder: options.MediaRecorder,
        Date,
        JSON,
        URLSearchParams,
        parseFloat,
        Math,
        setTimeout: (callback, delay) => clock.setTimeout(callback, delay),
        clearTimeout: (id) => clock.clearTimeout(id),
        setInterval: (callback, delay) => clock.setInterval(callback, delay),
        clearInterval: (id) => clock.clearInterval(id),
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
    context.window.navigator = context.navigator;
    context.window.MediaRecorder = context.MediaRecorder;
    vm.createContext(context);

    const keyboardHelperPath = path.join(filesDir, 'viewer-keyboard.js');
    if (fs.existsSync(keyboardHelperPath)) {
        vm.runInContext(fs.readFileSync(keyboardHelperPath, 'utf8'), context, {
            filename: keyboardHelperPath
        });
    }
    vm.runInContext(fs.readFileSync(path.join(filesDir, 'viewer.js'), 'utf8'), context, {
        filename: 'viewer.js'
    });

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
        remoteVideo: contextDocument.getElementById('remoteVideo'),
        keyboardSink: contextDocument.getElementById('keyboardSink'),
        messages,
        clock,
        filesDir,
        fetchCalls,
        webSockets,
        peerConnections
    };
}

function flushAsyncWork() {
    return new Promise((resolve) => setImmediate(resolve));
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

await test('Hangul composition commits only the completed syllable', () => {
    const { document, keyboardSink, messages, clock } = loadViewer();

    keyboardSink.focus();
    keyboardSink.dispatchEvent(textInputEvent('compositionstart', ''));
    document.dispatchEvent(keyboardEvent('ㄱ', { isComposing: true }));
    keyboardSink.dispatchEvent(
        textInputEvent('input', 'ㄱ', {
            inputType: 'insertCompositionText',
            isComposing: true
        })
    );
    keyboardSink.dispatchEvent(textInputEvent('compositionupdate', '가'));
    keyboardSink.dispatchEvent(textInputEvent('compositionend', '가'));
    keyboardSink.dispatchEvent(textInputEvent('input', '가'));
    clock.runAll();

    assert.deepEqual(messages, [
        { type: 'text', action: 'commit', text: '가', seq: 1 }
    ]);
});

await test('Latin text is committed from input events instead of keydown events', () => {
    const { document, keyboardSink, messages, clock } = loadViewer();

    keyboardSink.focus();
    document.dispatchEvent(keyboardEvent('a'));
    assert.deepEqual(messages, []);

    keyboardSink.dispatchEvent(textInputEvent('input', 'a'));
    clock.runAll();
    assert.deepEqual(messages, [
        { type: 'text', action: 'commit', text: 'a', seq: 1 }
    ]);
});

await test('rapid text input is batched into one remote commit', () => {
    const { keyboardSink, messages, clock } = loadViewer();

    keyboardSink.focus();
    keyboardSink.dispatchEvent(textInputEvent('input', 'a'));
    keyboardSink.dispatchEvent(textInputEvent('input', 'b'));
    keyboardSink.dispatchEvent(textInputEvent('input', 'c'));

    assert.deepEqual(messages, []);
    clock.runAll();
    assert.deepEqual(messages, [
        { type: 'text', action: 'commit', text: 'abc', seq: 1 }
    ]);
});

await test('rapid text waits for Android ACK before next commit is sent', () => {
    const { context, keyboardSink, messages, clock } = loadViewer();

    keyboardSink.focus();
    keyboardSink.dispatchEvent(textInputEvent('input', 'a'));
    clock.runAll();
    keyboardSink.dispatchEvent(textInputEvent('input', 'b'));
    clock.runAll();

    assert.deepEqual(messages, [
        { type: 'text', action: 'commit', text: 'a', seq: 1 }
    ]);

    vm.runInContext('handleControlAck({ seq: 1, applied: true });', context);

    assert.deepEqual(messages, [
        { type: 'text', action: 'commit', text: 'a', seq: 1 },
        { type: 'text', action: 'commit', text: 'b', seq: 2 }
    ]);
});

await test('text ACK queue resets when DataChannel closes before ACK', () => {
    const { context, keyboardSink, messages, clock } = loadViewer();

    keyboardSink.focus();
    keyboardSink.dispatchEvent(textInputEvent('input', 'a'));
    clock.runAll();
    keyboardSink.dispatchEvent(textInputEvent('input', 'b'));
    clock.runAll();

    assert.deepEqual(messages, [
        { type: 'text', action: 'commit', text: 'a', seq: 1 }
    ]);

    vm.runInContext('setupDataChannelHandlers(channel); channel.onclose();', context);
    keyboardSink.dispatchEvent(textInputEvent('input', 'c'));
    clock.runAll();

    assert.deepEqual(messages, [
        { type: 'text', action: 'commit', text: 'a', seq: 1 },
        { type: 'text', action: 'commit', text: 'c', seq: 1 }
    ]);
});

await test('stale signaling socket close does not close the current peer connection', async () => {
    const { context, webSockets, peerConnections } = loadViewer();

    vm.runInContext('connectSignaling();', context);
    webSockets[0].onopen();
    await flushAsyncWork();
    const firstSocket = webSockets[0];
    const originalOnClose = firstSocket.onclose;

    vm.runInContext('connectSignaling();', context);
    webSockets[1].onopen();
    await flushAsyncWork();
    const currentPeerConnection = peerConnections.at(-1);

    if (originalOnClose) {
        originalOnClose();
    }

    assert.equal(currentPeerConnection.closed, false);
    assert.equal(vm.runInContext('peerConnection === null', context), false);
});

await test('stale DataChannel close does not reset current text ACK state', () => {
    const { context } = loadViewer();

    const inFlightSeq = vm.runInContext(`
        const staleChannel = {};
        dataChannel = channel;
        inFlightTextSeq = 9;
        queuedTextPayloads = [{ type: 'text', action: 'commit', text: 'x' }];
        setupDataChannelHandlers(staleChannel);
        staleChannel.onclose();
        inFlightTextSeq;
    `, context);

    assert.equal(inFlightSeq, 9);
    assert.equal(vm.runInContext('queuedTextPayloads.length', context), 1);
});

await test('control DataChannel uses reliable delivery', () => {
    const { filesDir } = loadViewer();
    const viewerSource = fs.readFileSync(path.join(filesDir, 'viewer.js'), 'utf8');

    assert.doesNotMatch(viewerSource, /maxRetransmits\s*:\s*0/);
    assert.doesNotMatch(viewerSource, /maxPacketLifeTime\s*:/);
});

await test('Backspace sends remote delete when not composing', () => {
    const { document, keyboardSink, messages } = loadViewer();

    keyboardSink.focus();
    const event = keyboardEvent('Backspace');
    document.dispatchEvent(event);

    assert.equal(event.defaultPrevented, true);
    assert.deepEqual(messages, [
        { type: 'text', action: 'deleteBackward', count: 1, seq: 1 }
    ]);
});

await test('Backspace stays local while IME is composing text', () => {
    const { document, keyboardSink, messages } = loadViewer();

    keyboardSink.focus();
    keyboardSink.dispatchEvent(textInputEvent('compositionstart', ''));
    const event = keyboardEvent('Backspace', { isComposing: true });
    document.dispatchEvent(event);

    assert.equal(event.defaultPrevented, false);
    assert.deepEqual(messages, []);
});

await test('favorite app shortcuts render launch buttons', async () => {
    const { context, document, fetchCalls } = loadViewer();

    vm.runInContext(
        'renderFavoriteApps([{ packageName: "com.chat", label: "Chat" }]);',
        context
    );

    const list = document.getElementById('favoriteAppsList');
    assert.equal(list.children.length, 1);
    assert.equal(list.children[0].textContent, 'Chat');

    await list.children[0].listeners.get('click')[0]();

    const launchCall = fetchCalls.at(-1);
    assert.equal(launchCall.url, '/apps/launch');
    assert.equal(launchCall.options.method, 'POST');
    assert.equal(launchCall.options.headers['Content-Type'], 'application/json');
    assert.equal(launchCall.options.body, JSON.stringify({ packageName: 'com.chat' }));
});

await test('bottom navigation buttons send Android global key events', () => {
    const { document, messages } = loadViewer();

    document.getElementById('navBackBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    document.getElementById('navHomeBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    document.getElementById('navRecentsBtn').dispatchEvent({ type: 'click', preventDefault() {} });

    assert.deepEqual(messages, [
        { type: 'key', keyCode: 4 },
        { type: 'key', keyCode: 3 },
        { type: 'key', keyCode: 187 }
    ]);
});

await test('data usage display accumulates selected WebRTC candidate pair bytes in MB', async () => {
    const { context, document } = loadViewer();

    context.statsReports = new Map([
        ['pair', { type: 'candidate-pair', state: 'succeeded', selected: true, bytesSent: 1048576, bytesReceived: 2097152 }]
    ]);
    vm.runInContext('peerConnection = { getStats: async () => statsReports };', context);
    await vm.runInContext('resetDataUsageStats(); sampleWebRtcStats();', context);

    context.statsReports = new Map([
        ['pair', { type: 'candidate-pair', state: 'succeeded', selected: true, bytesSent: 2097152, bytesReceived: 5242880 }]
    ]);
    await vm.runInContext('sampleWebRtcStats();', context);

    assert.equal(document.getElementById('uploadUsage').textContent, '1.00 MB');
    assert.equal(document.getElementById('downloadUsage').textContent, '3.00 MB');
});

await test('stream quality buttons post selected mode to Android host', async () => {
    const { context, document, fetchCalls } = loadViewer();

    vm.runInContext('renderStreamQualityStatus({ selectedMode: "AUTO", effectiveMode: "HIGH", networkTransport: "WIFI", width: 1080, height: 2400, fps: 30, maxBitrateBps: 3000000 });', context);

    const standardButton = document.getElementById('qualityStandardBtn');
    await standardButton.listeners.get('click')[0]();

    const qualityCall = fetchCalls.at(-1);
    assert.equal(qualityCall.url, '/stream/quality');
    assert.equal(qualityCall.options.method, 'POST');
    assert.equal(qualityCall.options.headers['Content-Type'], 'application/json');
    assert.equal(qualityCall.options.body, JSON.stringify({ mode: 'STANDARD' }));
});

await test('system control buttons send volume and power key events', () => {
    const { document, messages } = loadViewer();

    document.getElementById('volUpBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    document.getElementById('volDownBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    document.getElementById('muteBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    document.getElementById('powerBtn').dispatchEvent({ type: 'click', preventDefault() {} });

    assert.deepEqual(messages, [
        { type: 'key', keyCode: 24 },
        { type: 'key', keyCode: 25 },
        { type: 'key', keyCode: 164 },
        { type: 'key', keyCode: 26 }
    ]);
});

await test('copy event sends clipboard payload through dataChannel once', async () => {
    const { context, messages, clock } = loadViewer({
        navigator: {
            clipboard: {
                readText: async () => 'copied-from-mac',
                writeText: async () => {}
            }
        }
    });

    const copyEvent = { type: 'copy', preventDefault() {} };
    context.document.dispatchEvent(copyEvent);

    clock.runAll();
    await flushAsyncWork();

    assert.deepEqual(messages, [
        { type: 'clipboard', text: 'copied-from-mac' }
    ]);
});

await test('copy event propagates empty clipboard text as clear command', async () => {
    const { context, messages, clock } = loadViewer({
        navigator: {
            clipboard: {
                readText: async () => '',
                writeText: async () => {}
            }
        }
    });

    context.document.dispatchEvent({ type: 'copy', preventDefault() {} });

    clock.runAll();
    await flushAsyncWork();

    assert.deepEqual(messages, [
        { type: 'clipboard', text: '' }
    ]);
});

await test('received clipboard text uses manual fallback when Clipboard API is unavailable', async () => {
    const { context, document } = loadViewer({ navigator: {} });

    vm.runInContext(
        'dataChannel.onmessage({ data: JSON.stringify({ type: "clipboard", text: "from-android" }) });',
        context
    );

    const toastContainer = document.getElementById('toastContainer');
    assert.equal(toastContainer.children.length, 1);
    assert.match(toastContainer.children[0].textContent, /클립보드 수신/);
});

await test('manual connect click refreshes an existing signaling session immediately', async () => {
    const { context, document, webSockets } = loadViewer();

    vm.runInContext('connectSignaling();', context);
    webSockets[0].onopen();
    await flushAsyncWork();

    document.getElementById('connectBtn').dispatchEvent({ type: 'click', preventDefault() {} });

    assert.equal(webSockets.length, 2);
});

await test('auto reconnect schedules backoff after closing an open signaling socket', async () => {
    const { context, webSockets, clock } = loadViewer();

    vm.runInContext('connectSignaling();', context);
    webSockets[0].onopen();
    await flushAsyncWork();

    vm.runInContext('triggerAutoReconnect();', context);

    assert.equal(webSockets.length, 1);
    clock.tick(1000);

    assert.equal(webSockets.length, 2);
});

await test('record button handles missing MediaRecorder without throwing', () => {
    const { document, remoteVideo } = loadViewer({ MediaRecorder: undefined });
    remoteVideo.srcObject = {};

    assert.doesNotThrow(() => {
        document.getElementById('recordBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    });
});

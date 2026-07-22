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
                clearRect(x, y, w, h) {},
                drawImage(image, sx, sy, sw, sh, dx, dy, dw, dh) {}
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
        this[name] = '';
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
    const viewerUrl = new URL(options.url || 'http://example.test:8080/');
    const fetchCalls = [];
    const webSockets = [];
    const peerConnections = [];
    const objectUrls = [];
    const revokedObjectUrls = [];
    class FakeBlob {
        constructor(parts = [], options = {}) {
            this.parts = parts;
            this.type = options.type || '';
            this.size = parts.reduce((total, part) => {
                if (typeof part === 'string') return total + part.length;
                if (typeof part?.byteLength === 'number') return total + part.byteLength;
                if (typeof part?.size === 'number') return total + part.size;
                return total;
            }, 0);
        }
    }
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
        RTCPeerConnection: FakeRTCPeerConnection,
        RTCSessionDescription: class {},
        RTCIceCandidate: class {},
        navigator: options.navigator || {},
        MediaRecorder: options.MediaRecorder,
        Blob: options.Blob || FakeBlob,
        Date,
        JSON,
        URL: FakeURL,
        URLSearchParams,
        parseFloat,
        Math,
        setTimeout: (callback, delay) => clock.setTimeout(callback, delay),
        clearTimeout: (id) => clock.clearTimeout(id),
        setInterval: (callback, delay) => clock.setInterval(callback, delay),
        clearInterval: (id) => clock.clearInterval(id),
        requestAnimationFrame: (callback) => clock.setTimeout(callback, 16),
        cancelAnimationFrame: (id) => clock.clearTimeout(id),
        createImageBitmap: undefined,
        VideoDecoder: options.VideoDecoder,
        EncodedVideoChunk: options.EncodedVideoChunk,
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
    context.window.Blob = context.Blob;
    context.window.URL = context.URL;
    context.window.VideoDecoder = context.VideoDecoder;
    context.window.EncodedVideoChunk = context.EncodedVideoChunk;
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

    const videoContainer = contextDocument.getElementById('videoContainer');
    const usbCanvas = contextDocument.getElementById('usbCanvas');
    videoContainer.appendChild(usbCanvas);

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
        peerConnections,
        objectUrls,
        revokedObjectUrls
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
    const viewerSource = fs.readFileSync(path.join(filesDir, 'webrtc.js'), 'utf8');

    assert.doesNotMatch(viewerSource, /maxRetransmits\s*:\s*0/);
    assert.doesNotMatch(viewerSource, /maxPacketLifeTime\s*:/);
});

await test('web UI cache-busts viewer scripts after app updates', () => {
    const appRoot = path.resolve(import.meta.dirname, '../../..');
    const html = fs.readFileSync(path.join(appRoot, 'src/main/resources/files/index.html'), 'utf8');

    assert.match(html, /src="\/viewer-keyboard\.js\?v=[^"]+"/);
    assert.match(html, /src="main\.js"/);
});

await test('initial transport follows explicit usb query parameter', () => {
    const { context } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });

    assert.equal(vm.runInContext('selectedTransport', context), 'usb');
});

await test('initial transport defaults to tailscale for phone hostnames', () => {
    const { context } = loadViewer({
        url: 'http://phone.ts.net:8080/'
    });

    assert.equal(vm.runInContext('selectedTransport', context), 'tailscale');
});

await test('viewer no longer reads token query or sends auth headers', () => {
    const { context, fetchCalls, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?token=legacy&transport=usb'
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onopen?.();

    assert.equal(webSockets[0].url, 'ws://127.0.0.1:8080/usb/session?codec=jpeg');
    assert.equal(vm.runInContext('typeof viewerAccessToken', context), 'undefined');
    assert.equal(fetchCalls.at(-1).url, '/debug/perf');
    assert.deepEqual(fetchCalls.at(-1).options.headers || {}, {});
});

await test('USB mode opens local session socket and sends raw control JSON', () => {
    const { context, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });

    vm.runInContext('connectMirror();', context);

    assert.equal(webSockets.length, 1);
    assert.equal(webSockets[0].url, 'ws://127.0.0.1:8080/usb/session?codec=jpeg');

    assert.equal(vm.runInContext('sendControlPayload({ type: "key", keyCode: 4 });', context), true);
    assert.deepEqual(webSockets[0].sentMessages, [
        JSON.stringify({ type: 'key', keyCode: 4 })
    ]);
});

await test('USB mode requests h264 session when WebCodecs are available', () => {
    class FakeVideoDecoder {
        static async isConfigSupported(config) {
            return { supported: true, config };
        }
    }
    class FakeEncodedVideoChunk {}
    const { context, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb',
        VideoDecoder: FakeVideoDecoder,
        EncodedVideoChunk: FakeEncodedVideoChunk
    });

    vm.runInContext('connectMirror();', context);

    assert.equal(webSockets.length, 1);
    assert.equal(webSockets[0].url, 'ws://127.0.0.1:8080/usb/session?codec=h264');
    assert.equal(webSockets[0].binaryType, 'arraybuffer');
});

await test('connect button toggles between USB connect and disconnect', () => {
    const { document, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });
    const connectButton = document.getElementById('connectBtn');

    assert.equal(connectButton.textContent, '미러링 연결하기');

    connectButton.dispatchEvent({ type: 'click' });

    assert.equal(webSockets.length, 1);
    assert.equal(connectButton.textContent, '미러링 연결 해제');
    assert.equal(connectButton.classList.contains('disconnect'), true);

    connectButton.dispatchEvent({ type: 'click' });

    assert.equal(webSockets[0].readyState, 3);
    assert.equal(connectButton.textContent, '미러링 연결하기');
    assert.equal(connectButton.classList.contains('disconnect'), false);
    assert.match(document.getElementById('connectionPlaceholder').textContent, /연결이 해제되었습니다/);
});

await test('web UI does not render redundant USB forward helper', () => {
    const appRoot = path.resolve(import.meta.dirname, '../../..');
    const html = fs.readFileSync(path.join(appRoot, 'src/main/resources/files/index.html'), 'utf8');

    for (const removedText of ['usbForwardPanel', 'usbForwardCommand', 'copyUsbForwardBtn', 'adb forward tcp:8080 tcp:8080']) {
        assert.equal(html.includes(removedText), false, `${removedText} should be removed from index.html`);
    }
});

await test('renders USB thermal and perf status from USB_STATUS', () => {
    const { context, document } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });

    vm.runInContext(`
        handleUsbTextMessage(JSON.stringify({
            type: 'USB_STATUS',
            payload: {
                captureReady: true,
                accessibilityReady: true,
                streamQuality: {
                    selectedMode: 'AUTO',
                    effectiveTier: 'BALANCED',
                    effectiveWidth: 540,
                    effectiveHeight: 1200,
                    effectiveFps: 8,
                    jpegQuality: 60,
                    policy: 'heat-first'
                },
                usbPerf: {
                    thermalStatus: 'NORMAL',
                    bytesPerSecond: 1200000,
                    lastEncodeMillis: 18,
                    averageEncodeMillis: 20,
                    framesSkippedByStillness: 5
                },
                message: 'USB_STREAMING'
            }
        }));
    `, context);

    const status = document.getElementById('usbCoolingStatus');
    assert.equal(document.getElementById('usbCoolingStatusItem').hidden, false);
    assert.match(status.textContent, /USB BALANCED/);
    assert.match(status.textContent, /540x1200/);
    assert.match(status.textContent, /8fps/);
    assert.match(status.textContent, /q60/);
    assert.match(status.textContent, /1.2 MB\/s/);
    assert.match(status.textContent, /encode 18ms/);
    assert.match(status.textContent, /thermal NORMAL/);
});

await test('USB_VIDEO_CONFIG configures WebCodecs decoder and decodes one key packet', async () => {
    const supportedConfigs = [];
    const configuredDecoders = [];
    const decodedChunks = [];
    class FakeVideoDecoder {
        constructor(init) {
            this.init = init;
            this.decodeQueueSize = 0;
            configuredDecoders.push(this);
        }

        static async isConfigSupported(config) {
            supportedConfigs.push(config);
            return { supported: true, config };
        }

        configure(config) {
            this.config = config;
        }

        decode(chunk) {
            decodedChunks.push(chunk);
        }

        close() {
            this.closed = true;
        }
    }
    class FakeEncodedVideoChunk {
        constructor(init) {
            Object.assign(this, init);
        }
    }
    const { context, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb',
        VideoDecoder: FakeVideoDecoder,
        EncodedVideoChunk: FakeEncodedVideoChunk
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onmessage({
        data: JSON.stringify({
            type: 'USB_VIDEO_CONFIG',
            payload: {
                codec: 'h264',
                codecString: 'avc1.42E01F',
                chunkFormat: 'annexb',
                codedWidth: 360,
                codedHeight: 800,
                fps: 30,
                maxBitrateBps: 3000000
            }
        })
    });
    await flushAsyncWork();

    vm.runInContext(`
        const packet = new ArrayBuffer(20);
        const bytes = new Uint8Array(packet);
        bytes[0] = 0x47; bytes[1] = 0x48; bytes[2] = 0x32; bytes[3] = 0x36;
        bytes[4] = 0x01;
        bytes[5] = 0x01;
        bytes[6] = 0x01;
        new DataView(packet).setBigInt64(8, 123456n, false);
        bytes.set([9, 8, 7, 6], 16);
        usbSocket.onmessage({ data: packet });
    `, context);

    assert.equal(supportedConfigs[0].codec, 'avc1.42E01F');
    assert.equal(supportedConfigs[0].codedWidth, 360);
    assert.equal(supportedConfigs[0].codedHeight, 800);
    assert.equal(supportedConfigs[0].optimizeForLatency, true);
    assert.equal(supportedConfigs[0].avc.format, 'annexb');
    assert.equal(configuredDecoders[0].config.codec, 'avc1.42E01F');
    assert.equal(configuredDecoders[0].config.avc.format, 'annexb');
    assert.equal(decodedChunks.length, 1);
    assert.equal(decodedChunks[0].type, 'key');
    assert.equal(decodedChunks[0].timestamp, 123456);
    assert.deepEqual([...new Uint8Array(decodedChunks[0].data)], [9, 8, 7, 6]);
});

await test('USB H.264 drops delta packets before first keyframe without JPEG fallback', async () => {
    const decodedChunks = [];
    class FakeVideoDecoder {
        constructor() {
            this.decodeQueueSize = 0;
        }

        static async isConfigSupported(config) {
            return { supported: true, config };
        }

        configure(config) {
            this.config = config;
        }

        decode(chunk) {
            decodedChunks.push(chunk);
            if (chunk.type === 'delta') {
                throw new Error(
                    "Failed to execute 'decode' on 'VideoDecoder': A key frame is required after configure() or flush()."
                );
            }
        }

        close() {}
    }
    class FakeEncodedVideoChunk {
        constructor(init) {
            Object.assign(this, init);
        }
    }
    const { context, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb',
        VideoDecoder: FakeVideoDecoder,
        EncodedVideoChunk: FakeEncodedVideoChunk
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onmessage({
        data: JSON.stringify({
            type: 'USB_VIDEO_CONFIG',
            payload: {
                codecString: 'avc1.42E01F',
                codedWidth: 360,
                codedHeight: 800
            }
        })
    });
    await flushAsyncWork();

    vm.runInContext(`
        const packet = new ArrayBuffer(20);
        const bytes = new Uint8Array(packet);
        bytes[0] = 0x47; bytes[1] = 0x48; bytes[2] = 0x32; bytes[3] = 0x36;
        bytes[4] = 0x01;
        bytes[5] = 0x01;
        bytes[6] = 0x00;
        new DataView(packet).setBigInt64(8, 123456n, false);
        bytes.set([1, 2, 3, 4], 16);
        usbSocket.onmessage({ data: packet });
    `, context);

    assert.equal(decodedChunks.length, 0);
    assert.equal(webSockets.length, 1);
    assert.equal(webSockets[0].url, 'ws://127.0.0.1:8080/usb/session?codec=h264');
});

await test('USB H.264 decodes codec config packets before first keyframe without JPEG fallback', async () => {
    const decodedChunks = [];
    class FakeVideoDecoder {
        constructor() {
            this.decodeQueueSize = 0;
        }

        static async isConfigSupported(config) {
            return { supported: true, config };
        }

        configure(config) {
            this.config = config;
        }

        decode(chunk) {
            decodedChunks.push(chunk);
            if (this.config?.avc?.format !== 'annexb') {
                throw new Error(
                    "Failed to execute 'decode' on 'VideoDecoder': AVC formatted H.264 requires a description field."
                );
            }
        }

        close() {}
    }
    class FakeEncodedVideoChunk {
        constructor(init) {
            Object.assign(this, init);
        }
    }
    const { context, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb',
        VideoDecoder: FakeVideoDecoder,
        EncodedVideoChunk: FakeEncodedVideoChunk
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onmessage({
        data: JSON.stringify({
            type: 'USB_VIDEO_CONFIG',
            payload: {
                codecString: 'avc1.42E01F',
                chunkFormat: 'annexb',
                codedWidth: 360,
                codedHeight: 800
            }
        })
    });
    await flushAsyncWork();

    vm.runInContext(`
        const packet = new ArrayBuffer(20);
        const bytes = new Uint8Array(packet);
        bytes[0] = 0x47; bytes[1] = 0x48; bytes[2] = 0x32; bytes[3] = 0x36;
        bytes[4] = 0x01;
        bytes[5] = 0x01;
        bytes[6] = 0x03;
        new DataView(packet).setBigInt64(8, 123456n, false);
        bytes.set([0, 0, 0, 1], 16);
        usbSocket.onmessage({ data: packet });
    `, context);

    assert.equal(decodedChunks.length, 1);
    assert.equal(decodedChunks[0].type, 'key');
    assert.equal(decodedChunks[0].timestamp, 123456);
    assert.deepEqual([...new Uint8Array(decodedChunks[0].data)], [0, 0, 0, 1]);
    assert.equal(webSockets.length, 1);
    assert.equal(webSockets[0].url, 'ws://127.0.0.1:8080/usb/session?codec=h264');
});

await test('USB H.264 unsupported config reconnects as JPEG', async () => {
    class FakeVideoDecoder {
        static async isConfigSupported(config) {
            return { supported: false, config };
        }
    }
    class FakeEncodedVideoChunk {}
    const { context, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb',
        VideoDecoder: FakeVideoDecoder,
        EncodedVideoChunk: FakeEncodedVideoChunk
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onmessage({
        data: JSON.stringify({
            type: 'USB_VIDEO_CONFIG',
            payload: {
                codec: 'avc1.42E01F',
                codedWidth: 360,
                codedHeight: 800
            }
        })
    });
    await flushAsyncWork();

    assert.equal(webSockets.length, 2);
    assert.equal(webSockets[0].readyState, 3);
    assert.equal(webSockets[1].url, 'ws://127.0.0.1:8080/usb/session?codec=jpeg');
    assert.equal(webSockets[1].binaryType, 'blob');
});

await test('USB H.264 startup failure status reconnects as JPEG', async () => {
    class FakeVideoDecoder {}
    class FakeEncodedVideoChunk {}
    const { context, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb',
        VideoDecoder: FakeVideoDecoder,
        EncodedVideoChunk: FakeEncodedVideoChunk
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onmessage({
        data: JSON.stringify({
            type: 'USB_STATUS',
            payload: {
                captureReady: false,
                message: 'H264_START_FAILED'
            }
        })
    });

    assert.equal(webSockets.length, 2);
    assert.equal(webSockets[1].url, 'ws://127.0.0.1:8080/usb/session?codec=jpeg');
});

await test('USB H.264 status renders compact resolution fps and Mbps chips', () => {
    const { context, document } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });

    vm.runInContext(`
        handleUsbTextMessage(JSON.stringify({
            type: 'USB_STATUS',
            payload: {
                streamQuality: {
                    codec: 'h264',
                    effectiveTier: 'HIGH',
                    effectiveWidth: 720,
                    effectiveHeight: 1600,
                    effectiveFps: 30,
                    maxBitrateBps: 3000000
                },
                usbPerf: {
                    bytesPerSecond: 375000
                }
            }
        }));
    `, context);

    const status = document.getElementById('usbCoolingStatus');
    assert.match(status.textContent, /H\.264/);
    assert.match(status.textContent, /720x1600/);
    assert.match(status.textContent, /30fps/);
    assert.match(status.textContent, /3\.0Mbps/);
});

await test('USB perf polling requests debug perf while USB socket is active', () => {
    const { context, fetchCalls, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onopen?.();

    assert.equal(fetchCalls.at(-1).url, '/debug/perf');
    assert.deepEqual(fetchCalls.at(-1).options.headers || {}, {});
});

await test('USB binary frame renders blob image and updates download usage', () => {
    const { context, document, webSockets, objectUrls } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });

    vm.runInContext('connectMirror();', context);
    vm.runInContext(`
        const frame = new Blob([new Uint8Array([1, 2, 3, 4])], { type: 'image/jpeg' });
        usbSocket.onmessage({ data: frame });
    `, context);

    assert.equal(document.getElementById('usbFrame').src, 'blob:fake-1');
    assert.equal(document.getElementById('downloadUsage').textContent, '0.00 MB');
    assert.equal(objectUrls[0].blob.size, 4);
    assert.equal(webSockets[0].binaryType, 'blob');
});

await test('USB frame taps send normalized tap controls through USB socket', () => {
    const { context, document, webSockets } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onopen();
    vm.runInContext(`
        const frame = new Blob([new Uint8Array([1, 2, 3, 4])], { type: 'image/jpeg' });
        usbSocket.onmessage({ data: frame });
    `, context);

    const usbFrame = document.getElementById('usbFrame');
    usbFrame.dispatchEvent({
        type: 'mousedown',
        clientX: 180,
        clientY: 400,
        preventDefault() {}
    });
    usbFrame.dispatchEvent({
        type: 'mouseup',
        clientX: 180,
        clientY: 400,
        preventDefault() {}
    });

    assert.deepEqual(webSockets[0].sentMessages, [
        JSON.stringify({ type: 'tap', x: 0.5, y: 0.5 })
    ]);
});

await test('USB frame mouse wheel sends swipe controls through USB socket', () => {
    const { context, document, webSockets, clock } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb'
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onopen();
    vm.runInContext(`
        const frame = new Blob([new Uint8Array([1, 2, 3, 4])], { type: 'image/jpeg' });
        usbSocket.onmessage({ data: frame });
    `, context);

    const usbFrame = document.getElementById('usbFrame');
    const wheelEvent = {
        type: 'wheel',
        clientX: 180,
        clientY: 400,
        deltaY: 360,
        deltaX: 0,
        defaultPrevented: false,
        preventDefault() {
            this.defaultPrevented = true;
        }
    };
    usbFrame.dispatchEvent(wheelEvent);
    clock.runAll();

    assert.equal(wheelEvent.defaultPrevented, true);
    assert.deepEqual(webSockets[0].sent, [
        {
            type: 'swipe',
            x1: 0.5,
            y1: 0.7,
            x2: 0.5,
            y2: 0.3,
            duration: 180
        }
    ]);
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

await test('web UI does not render capture or audio controls', () => {
    const appRoot = path.resolve(import.meta.dirname, '../../..');
    const html = fs.readFileSync(path.join(appRoot, 'src/main/resources/files/index.html'), 'utf8');

    for (const removedId of ['screenshotBtn', 'recordBtn', 'volUpBtn', 'volDownBtn', 'muteBtn']) {
        assert.equal(html.includes(`id="${removedId}"`), false, `${removedId} should be removed from index.html`);
    }
});

await test('USB cooling status uses stacked non-overlapping layout', () => {
    const appRoot = path.resolve(import.meta.dirname, '../../..');
    const html = fs.readFileSync(path.join(appRoot, 'src/main/resources/files/index.html'), 'utf8');

    assert.match(html, /id="usbCoolingStatusItem"[^>]*class="usb-cooling-card"/);
    assert.match(html, /\.usb-cooling-card\s*\{[^}]*grid-template-columns:\s*1fr;/s);
    assert.match(html, /\.usb-cooling-value\s*\{[^}]*overflow-wrap:\s*anywhere;/s);
});

await test('USB mode hides Tailscale-only rows', () => {
    const { document } = loadViewer({ url: 'http://127.0.0.1:8080/?transport=usb' });

    assert.equal(document.getElementById('streamStatusLabel').textContent, 'USB 스트림');
    assert.equal(document.getElementById('rtcLatencyItem').hidden, true);
    assert.equal(document.getElementById('qualityNetworkItem').hidden, true);
    assert.equal(document.getElementById('toolsPanel').open, false);
});

await test('Tailscale mode hides USB-only helper and keeps network rows visible', () => {
    const { document } = loadViewer({ url: 'http://phone.tailnet.ts.net:8080/?transport=tailscale' });

    assert.equal(document.getElementById('streamStatusLabel').textContent, 'WebRTC 스트림');
    assert.equal(document.getElementById('rtcLatencyItem').hidden, false);
    assert.equal(document.getElementById('qualityNetworkItem').hidden, false);
    assert.equal(document.getElementById('usbCoolingStatusItem').hidden, true);
});

await test('system controls send only the remaining power key event', () => {
    const { document, messages } = loadViewer();

    document.getElementById('volUpBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    document.getElementById('volDownBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    document.getElementById('muteBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    document.getElementById('powerBtn').dispatchEvent({ type: 'click', preventDefault() {} });

    assert.deepEqual(messages, [
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

await test('copy event sends clipboard payload through USB socket in usb mode', async () => {
    const { context, webSockets, clock } = loadViewer({
        url: 'http://127.0.0.1:8080/?transport=usb',
        navigator: {
            clipboard: {
                readText: async () => 'copied-over-usb',
                writeText: async () => {}
            }
        }
    });

    vm.runInContext('connectMirror();', context);
    webSockets[0].onopen();
    context.document.dispatchEvent({ type: 'copy', preventDefault() {} });

    clock.runAll();
    await flushAsyncWork();

    assert.deepEqual(webSockets[0].sentMessages, [
        JSON.stringify({ type: 'clipboard', text: 'copied-over-usb' })
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

await test('manual connect click disconnects an existing signaling session', async () => {
    const { context, document, webSockets } = loadViewer();

    vm.runInContext('connectSignaling();', context);
    webSockets[0].onopen();
    await flushAsyncWork();

    document.getElementById('connectBtn').dispatchEvent({ type: 'click', preventDefault() {} });

    assert.equal(webSockets.length, 1);
    assert.equal(webSockets[0].readyState, 3);
    assert.equal(document.getElementById('connectBtn').textContent, '미러링 연결하기');
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

await test('waiting for screen capture stops reconnect overlay and clears stale video', () => {
    const { context, document, remoteVideo } = loadViewer();
    const overlay = document.getElementById('reconnectOverlay');
    remoteVideo.srcObject = { stale: true };
    remoteVideo.videoWidth = 2;
    remoteVideo.videoHeight = 2;

    vm.runInContext('showReconnectOverlayProgress(1, 1);', context);
    assert.equal(overlay.classList.contains('hidden'), false);

    vm.runInContext('applyAndroidStatusMessage("WAITING_FOR_SCREEN_CAPTURE");', context);

    assert.equal(overlay.classList.contains('hidden'), true);
    assert.equal(remoteVideo.srcObject, null);
    assert.equal(document.getElementById('controlStatus').innerText, '대기');
    assert.match(document.getElementById('statusDetail').textContent, /화면 공유 권한/);
});

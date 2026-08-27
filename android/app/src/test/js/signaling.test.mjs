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
        if (event.type === 'click' && typeof this.onclick === 'function') {
            this.onclick(event);
        }
        return !event.defaultPrevented;
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

    querySelector(selector) {
        if (selector === '.pulse-ring') {
            if (!this._pulseRing) {
                this._pulseRing = new FakeEventTarget('pulseRing');
            }
            return this._pulseRing;
        }
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
            this.sent.push(typeof payload === 'string' ? JSON.parse(payload) : payload);
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

    return {
        context,
        document: contextDocument,
        clock,
        fetchCalls,
        webSockets,
        peerConnections
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

// -----------------------------------------------------------------------------
// Test Suite: signaling.js Unit Tests
// -----------------------------------------------------------------------------

await test('initialTransport selects transport based on URL query parameter and hostname', () => {
    const { context: contextDefault } = loadViewer({ url: 'http://example.test:8080/' });
    assert.equal(contextDefault._get_selectedTransport(), 'tailscale');

    const { context: contextUsbQuery } = loadViewer({ url: 'http://example.test:8080/?transport=usb' });
    assert.equal(contextUsbQuery._get_selectedTransport(), 'usb');

    const { context: contextLocalhost } = loadViewer({ url: 'http://127.0.0.1:8080/' });
    assert.equal(contextLocalhost._get_selectedTransport(), 'usb');
});

await test('setTransport updates active transport and UI element states', () => {
    const { context, document } = loadViewer({ url: 'http://example.test:8080/' });
    const btnTailscale = document.getElementById('transportTailscaleBtn');
    const btnUsb = document.getElementById('transportUsbBtn');

    assert.equal(context._get_selectedTransport(), 'tailscale');

    context.setTransport('usb');
    assert.equal(context._get_selectedTransport(), 'usb');
    assert.equal(btnUsb.classList.contains('active'), true);
    assert.equal(btnTailscale.classList.contains('active'), false);

    context.setTransport('tailscale');
    assert.equal(context._get_selectedTransport(), 'tailscale');
    assert.equal(btnTailscale.classList.contains('active'), true);
    assert.equal(btnUsb.classList.contains('active'), false);
});

await test('isSocketActive and isMirrorConnectionActive accurately report socket readiness', () => {
    const { context } = loadViewer();

    assert.equal(Boolean(context.isSocketActive(null)), false);
    assert.equal(Boolean(context.isMirrorConnectionActive()), false);

    const fakeSocket = { readyState: 1 }; // OPEN
    assert.equal(Boolean(context.isSocketActive(fakeSocket)), true);

    context._set_socket(fakeSocket);
    assert.equal(Boolean(context.isMirrorConnectionActive()), true);

    context._set_socket(null);
    assert.equal(Boolean(context.isMirrorConnectionActive()), false);
});

await test('connectSignaling opens WebSocket and configures open/close handlers', async () => {
    const { context, document, webSockets } = loadViewer({ url: 'http://example.test:8080/' });
    const wsStatus = document.getElementById('wsStatus');

    context.connectSignaling();
    assert.equal(webSockets.length, 1);
    const ws = webSockets[0];
    assert.equal(ws.url, 'ws://example.test:8080/signaling');

    // Simulate socket open
    ws.onopen();
    assert.equal(context._get_reconnectAttempts(), 0);
    assert.equal(context._get_isReconnecting(), false);
    assert.match(wsStatus.innerHTML, /Online/);

    // Simulate socket close
    ws.onclose({ code: 1000, reason: 'Normal Closure' });
    assert.match(wsStatus.innerHTML, /Offline/);
});

await test('closeSignalingSocket closes open signaling socket cleanly', () => {
    const { context, webSockets } = loadViewer();

    context.connectSignaling();
    const ws = webSockets[0];
    assert.equal(ws.readyState, 1); // OPEN

    context.closeSignalingSocket();
    assert.equal(ws.readyState, 3); // CLOSED
    assert.equal(context._get_socket(), null);
});

await test('connectUsbSession opens USB session socket and processes JSON status messages', () => {
    const { context, webSockets, document } = loadViewer();

    context.connectUsbSession('jpeg');
    assert.equal(webSockets.length, 1);
    const usbWs = webSockets[0];
    assert.equal(usbWs.url, 'ws://example.test:8080/usb/session?codec=jpeg');

    // Simulate text USB_STATUS message from USB session
    const statusPayload = JSON.stringify({
        type: 'USB_STATUS',
        payload: { captureReady: true, accessibilityReady: true }
    });
    usbWs.onmessage({ data: statusPayload });

    const rtcStatus = document.getElementById('rtcStatus');
    assert.equal(rtcStatus.innerText, 'USB 캡처 준비');
});

await test('reconnectUsbAsJpeg closes current decoder and reconnects with JPEG codec', () => {
    const { context, webSockets } = loadViewer();
    context._set_selectedTransport('usb');

    let decoderClosed = false;
    context._set_usbVideoDecoder({
        close() { decoderClosed = true; }
    });
    context._set_usbVideoDecoderConfigured(true);

    context.reconnectUsbAsJpeg('Android H.264 인코더 시작 실패로 JPEG로 전환합니다.');

    assert.equal(decoderClosed, true);
    assert.equal(context._get_forceUsbJpegFallback(), true);
    assert.equal(context._get_usbVideoDecoder(), null);

    const lastSocket = webSockets[webSockets.length - 1];
    assert.equal(lastSocket.url, 'ws://example.test:8080/usb/session?codec=jpeg');
});

await test('handleStatusMessage dispatches Android status messages and updates UI state', () => {
    const { context, document } = loadViewer();
    const rtcStatus = document.getElementById('rtcStatus');
    const accessibilityStatus = document.getElementById('accessibilityStatus');

    context.handleStatusMessage({ captureReady: true, accessibilityReady: true });
    assert.equal(rtcStatus.innerText, 'Capture Ready');
    assert.equal(accessibilityStatus.innerText, '활성화');

    context.handleStatusMessage({ message: 'WAITING_FOR_SCREEN_CAPTURE' });
    assert.equal(rtcStatus.innerText, '화면 공유 대기');
});

await test('applyAndroidStatusMessage handles SCREEN_CAPTURE_REAUTH_REQUIRED and disables autoReconnect', () => {
    const { context, document } = loadViewer();
    const rtcStatus = document.getElementById('rtcStatus');

    context._set_shouldAutoReconnect(true);
    context.applyAndroidStatusMessage('SCREEN_CAPTURE_REAUTH_REQUIRED');

    assert.equal(rtcStatus.innerText, '재승인 필요');
    assert.equal(context._get_shouldAutoReconnect(), false);
});

await test('applyAndroidStatusMessage handles PROJECTION_STOPPED_LOCKED and updates status detail', () => {
    const { context, document } = loadViewer();
    const rtcStatus = document.getElementById('rtcStatus');

    context.applyAndroidStatusMessage('PROJECTION_STOPPED_LOCKED');
    assert.equal(rtcStatus.innerText, '잠금으로 중단');
    assert.equal(context._get_shouldAutoReconnect(), false);
});

await test('triggerAutoReconnect schedules exponential backoff and stops at max reconnect attempts limit', () => {
    const { context, clock, document } = loadViewer({ url: 'http://example.test:8080/' });
    const overlay = document.getElementById('reconnectOverlay');
    const attemptsLabel = document.getElementById('reconnectAttempts');

    context._set_selectedTransport('tailscale');
    context._set_shouldAutoReconnect(true);

    // Initial trigger
    context.triggerAutoReconnect();
    assert.equal(context._get_isReconnecting(), true);
    assert.equal(context._get_reconnectAttempts(), 1);
    assert.equal(overlay.classList.contains('hidden'), false);
    assert.match(attemptsLabel.textContent, /시도 1\/8/);

    // Fast-forward through remaining attempts up to MAX_RECONNECT_ATTEMPTS (8)
    for (let i = 2; i <= 8; i++) {
        // Trigger reconnect on each socket close simulation or timeout fire
        clock.tick(16000);
        // During connectSignaling, socket close triggers triggerAutoReconnect or reconnect sequence
        const currentWs = context._get_socket();
        if (currentWs) {
            currentWs.onclose?.({ code: 1006, reason: 'Abnormal' });
        }
    }

    // Trigger attempt beyond max
    clock.tick(16000);
    const lastWs = context._get_socket();
    if (lastWs) {
        lastWs.onclose?.({ code: 1006, reason: 'Abnormal' });
    }

    assert.equal(context._get_isReconnecting(), false);
    assert.match(context._get_statusDetailMessage() || document.getElementById('statusDetail').textContent, /자동 재연결/);
});

await test('hideReconnectOverlay hides the reconnect overlay element', () => {
    const { context, document } = loadViewer();
    const overlay = document.getElementById('reconnectOverlay');

    overlay.classList.remove('hidden');
    context.hideReconnectOverlay();
    assert.equal(overlay.classList.contains('hidden'), true);
});

await test('disconnectMirrorFromButton closes active connection and updates connect button state', () => {
    const { context, webSockets } = loadViewer();

    context.connectSignaling();
    const ws = webSockets[0];
    assert.equal(ws.readyState, 1); // OPEN

    context.disconnectMirrorFromButton();
    assert.equal(ws.readyState, 3); // CLOSED
    assert.equal(Boolean(context.isMirrorConnectionActive()), false);
});

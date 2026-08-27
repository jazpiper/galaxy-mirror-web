import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';

class FakeEventTarget {
    constructor(id = '') {
        this._id = id;
        this.listeners = new Map();
        this.children = [];
        const classes = new Set();
        this.classList = {
            add(...names) { names.forEach(n => classes.add(n)); },
            remove(...names) { names.forEach(n => classes.delete(n)); },
            contains(name) { return classes.has(name); },
            toggle(name, force) {
                if (force === true) { classes.add(name); return true; }
                if (force === false) { classes.delete(name); return false; }
                if (classes.has(name)) { classes.delete(name); return false; }
                classes.add(name); return true;
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
        this.srcObject = null;
    }

    get id() { return this._id; }
    set id(val) {
        this._id = val;
        if (this.ownerDocument) {
            this.ownerDocument.elements.set(val, this);
        }
    }

    getContext(type) {
        if (type === '2d') {
            return {
                clearRect() {},
                drawImage() {}
            };
        }
        return null;
    }

    get innerHTML() { return this._innerHTML; }
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
    set textContent(value) { this._textContent = value; }

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
    setAttribute(name, value) { this[name] = value; }
    removeAttribute(name) { this[name] = ''; }
    setSelectionRange() {}
    querySelector() { return null; }
    getBoundingClientRect() { return { left: 0, top: 0, width: 360, height: 800 }; }
    get childElementCount() { return this.children.length; }
    get firstChild() { return this.children[0] || null; }
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

function createEnvironment(options = {}) {
    const appRoot = path.resolve(import.meta.dirname, '../../..');
    const filesDir = path.join(appRoot, 'src/main/resources/files');
    const contextDocument = new FakeDocument();
    const clock = new FakeClock();

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
            this.sent.push(JSON.parse(payload));
        }

        close(code = 1000, reason = '') {
            this.readyState = FakeWebSocket.CLOSED;
            this.onclose?.({ code, reason });
        }
    }

    class FakeDataChannel {
        constructor(label, options = {}) {
            this.label = label;
            this.options = options;
            this.readyState = 'open';
            this.sent = [];
            this.onopen = null;
            this.onclose = null;
            this.onmessage = null;
        }

        send(payload) {
            this.sent.push(typeof payload === 'string' ? JSON.parse(payload) : payload);
        }

        close() {
            this.readyState = 'closed';
            if (this.onclose) this.onclose();
        }
    }

    class FakeRTCPeerConnection {
        constructor(config) {
            this.config = config;
            this.closed = false;
            this.remoteDescription = null;
            this.localDescription = null;
            this.iceConnectionState = 'new';
            this.connectionState = 'new';
            this.onicecandidate = null;
            this.oniceconnectionstatechange = null;
            this.onconnectionstatechange = null;
            this.ontrack = null;
            this.transceivers = [];
            this.dataChannels = [];
            this.statsMap = new Map();
            peerConnections.push(this);
        }

        addTransceiver(kind, init) {
            const transceiver = { kind, init };
            this.transceivers.push(transceiver);
            return transceiver;
        }

        createDataChannel(label, options) {
            const dc = new FakeDataChannel(label, options);
            this.dataChannels.push(dc);
            return dc;
        }

        async createOffer() {
            return { type: 'offer', sdp: 'fake-sdp-offer' };
        }

        async setLocalDescription(desc) {
            this.localDescription = desc;
        }

        async setRemoteDescription(desc) {
            this.remoteDescription = desc;
        }

        async addIceCandidate(candidate) {
            this.addedCandidates = this.addedCandidates || [];
            this.addedCandidates.push(candidate);
        }

        async getStats() {
            return this.statsMap;
        }

        close() {
            this.closed = true;
            this.connectionState = 'closed';
        }
    }

    const context = {
        console,
        document: contextDocument,
        window: {
            location: { protocol: 'http:', host: 'example.test:8080', hostname: 'example.test', search: '', href: 'http://example.test:8080/', pathname: '/' },
            setTimeout: (cb, d) => clock.setTimeout(cb, d),
            clearTimeout: (id) => clock.clearTimeout(id),
            setInterval: (cb, d) => clock.setInterval(cb, d),
            clearInterval: (id) => clock.clearInterval(id),
            requestAnimationFrame: (cb) => clock.setTimeout(cb, 16),
            cancelAnimationFrame: (id) => clock.clearTimeout(id)
        },
        WebSocket: FakeWebSocket,
        RTCPeerConnection: FakeRTCPeerConnection,
        RTCSessionDescription: class { constructor(init) { Object.assign(this, init); } },
        RTCIceCandidate: class { constructor(init) { Object.assign(this, init); } },
        navigator: {},
        Date,
        JSON,
        URLSearchParams,
        parseFloat,
        Math,
        setTimeout: (cb, d) => clock.setTimeout(cb, d),
        clearTimeout: (id) => clock.clearTimeout(id),
        setInterval: (cb, d) => clock.setInterval(cb, d),
        clearInterval: (id) => clock.clearInterval(id),
        requestAnimationFrame: (cb) => clock.setTimeout(cb, 16),
        cancelAnimationFrame: (id) => clock.clearTimeout(id),
        fetch: async () => ({ ok: true, json: async () => ({ apps: [] }), text: async () => '' })
    };
    context.window.document = contextDocument;
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

    ['webrtc.js', 'controls.js', 'signaling.js', 'ui.js', 'main.js'].forEach(loadModuleFile);

    return {
        context,
        document: contextDocument,
        clock,
        webSockets,
        peerConnections
    };
}

test('extractNetworkBytes extracts selected candidate pair and fallback byte stats', () => {
    const env = createEnvironment();
    const { extractNetworkBytes } = env.context;

    const statsWithSelectedPair = new Map();
    statsWithSelectedPair.set('transport-1', {
        type: 'transport',
        selectedCandidatePairId: 'pair-1'
    });
    statsWithSelectedPair.set('pair-1', {
        type: 'candidate-pair',
        state: 'succeeded',
        selected: true,
        bytesSent: 1500,
        bytesReceived: 3000,
        currentRoundTripTime: 0.025
    });

    const result1 = extractNetworkBytes(statsWithSelectedPair);
    assert.equal(result1.current.sent, 1500);
    assert.equal(result1.current.received, 3000);
    assert.equal(result1.rtt, 0.025);

    const statsFallback = [
        { type: 'inbound-rtp', bytesReceived: 500 },
        { type: 'outbound-rtp', bytesSent: 200 },
        { type: 'data-channel', bytesSent: 50, bytesReceived: 100 }
    ];
    const result2 = extractNetworkBytes(statsFallback);
    assert.equal(result2.current.sent, 250);
    assert.equal(result2.current.received, 600);
    assert.equal(result2.rtt, null);
});

test('sampleWebRtcStats accumulates network bytes and updates latency display', async () => {
    const env = createEnvironment();
    const { sampleWebRtcStats, resetNetworkBytes, _set_peerConnection, _get_accumulatedNetworkBytes } = env.context;
    const rtcLatency = env.document.getElementById('rtcLatency');

    resetNetworkBytes();

    const mockStats1 = new Map([
        ['pair-1', { type: 'candidate-pair', state: 'succeeded', selected: true, bytesSent: 1000, bytesReceived: 2000, currentRoundTripTime: 0.015 }]
    ]);
    const mockPeerConnection = {
        async getStats() { return mockStats1; }
    };

    _set_peerConnection(mockPeerConnection);

    await sampleWebRtcStats();
    assert.equal(rtcLatency.textContent, '15 ms');
    assert.equal(_get_accumulatedNetworkBytes().sent, 0);
    assert.equal(_get_accumulatedNetworkBytes().received, 0);

    const mockStats2 = new Map([
        ['pair-1', { type: 'candidate-pair', state: 'succeeded', selected: true, bytesSent: 1500, bytesReceived: 3500, currentRoundTripTime: 0.020 }]
    ]);
    mockPeerConnection.getStats = async () => mockStats2;

    await sampleWebRtcStats();
    assert.equal(rtcLatency.textContent, '20 ms');
    assert.equal(_get_accumulatedNetworkBytes().sent, 500);
    assert.equal(_get_accumulatedNetworkBytes().received, 1500);
});

test('startDataUsagePolling and stopDataUsagePolling control polling timer', () => {
    const env = createEnvironment();
    const { startDataUsagePolling, stopDataUsagePolling, _get_dataUsagePollId } = env.context;

    assert.equal(_get_dataUsagePollId(), null);
    startDataUsagePolling();
    const pollId = _get_dataUsagePollId();
    assert.notEqual(pollId, null);

    stopDataUsagePolling();
    assert.equal(_get_dataUsagePollId(), null);
});

test('addRemoteCandidate and flushPendingRemoteCandidates manage candidate queue', async () => {
    const env = createEnvironment();
    const { addRemoteCandidate, flushPendingRemoteCandidates, _set_remoteDescriptionSet, _set_peerConnection, _get_pendingRemoteCandidates } = env.context;

    const added = [];
    const mockPeerConnection = {
        remoteDescription: { sdp: 'fake-sdp' },
        async addIceCandidate(cand) { added.push(cand); }
    };
    _set_peerConnection(mockPeerConnection);
    _set_remoteDescriptionSet(false);

    await addRemoteCandidate({ candidate: 'cand-1' });
    assert.equal(_get_pendingRemoteCandidates().length, 1);
    assert.equal(added.length, 0);

    _set_remoteDescriptionSet(true);
    await flushPendingRemoteCandidates();
    assert.equal(_get_pendingRemoteCandidates().length, 0);
    assert.equal(added.length, 1);
    assert.equal(added[0].candidate, 'cand-1');

    await addRemoteCandidate({ candidate: 'cand-2' });
    assert.equal(added.length, 2);
    assert.equal(added[1].candidate, 'cand-2');
});

test('setupWebRTC initializes PeerConnection, creates offer, and sends ICE candidates', async () => {
    const env = createEnvironment();
    const { setupWebRTC, _get_peerConnection, _set_socket } = env.context;

    const fakeSocket = new env.context.WebSocket('ws://example.test:8080/signaling');
    _set_socket(fakeSocket);

    await setupWebRTC(fakeSocket);

    const pc = _get_peerConnection();
    assert.notEqual(pc, null);
    assert.equal(pc.transceivers.length, 1);
    assert.equal(pc.transceivers[0].kind, 'video');
    assert.equal(pc.dataChannels.length, 1);
    assert.equal(pc.dataChannels[0].label, 'control');

    assert.equal(fakeSocket.sent.length, 1);
    assert.equal(fakeSocket.sent[0].type, 'OFFER');
    assert.equal(fakeSocket.sent[0].payload.sdp, 'fake-sdp-offer');

    pc.onicecandidate({ candidate: { candidate: 'candidate-abc' } });
    assert.equal(fakeSocket.sent.length, 2);
    assert.equal(fakeSocket.sent[1].type, 'ICE_CANDIDATE');
    assert.equal(fakeSocket.sent[1].payload.candidate, 'candidate-abc');
});

test('setupWebRTC connection state change triggers auto reconnect on failure', async () => {
    const env = createEnvironment();
    const { setupWebRTC, _get_peerConnection, _set_socket } = env.context;

    let autoReconnectTriggered = false;
    env.context.triggerAutoReconnect = () => { autoReconnectTriggered = true; };

    const fakeSocket = new env.context.WebSocket('ws://example.test:8080/signaling');
    _set_socket(fakeSocket);

    await setupWebRTC(fakeSocket);
    const pc = _get_peerConnection();

    pc.iceConnectionState = 'failed';
    pc.oniceconnectionstatechange();
    assert.equal(autoReconnectTriggered, true);

    autoReconnectTriggered = false;
    pc.connectionState = 'failed';
    pc.onconnectionstatechange();
    assert.equal(autoReconnectTriggered, true);
});

test('setupWebRTC handles ontrack event to attach stream to remoteVideo', async () => {
    const env = createEnvironment();
    const { setupWebRTC, _get_peerConnection, _set_socket } = env.context;

    const fakeSocket = new env.context.WebSocket('ws://example.test:8080/signaling');
    _set_socket(fakeSocket);

    await setupWebRTC(fakeSocket);
    const pc = _get_peerConnection();

    const remoteVideo = env.document.getElementById('remoteVideo');
    const fakeStream = { id: 'stream-1' };

    pc.ontrack({ streams: [fakeStream] });
    assert.equal(remoteVideo.srcObject, fakeStream);
    assert.equal(env.document.getElementById('rtcStatus').innerText, 'Streaming Active');
});

test('setupDataChannelHandlers handles open, close, CONTROL_ACK and clipboard messages', () => {
    const env = createEnvironment();
    const { setupDataChannelHandlers, _set_dataChannel } = env.context;

    class FakeChannel {
        constructor() {
            this.onopen = null;
            this.onclose = null;
            this.onmessage = null;
        }
    }

    const channel = new FakeChannel();
    _set_dataChannel(channel);
    setupDataChannelHandlers(channel);

    channel.onopen();
    assert.equal(env.document.getElementById('controlStatus').innerText, '채널 연결됨');

    channel.onmessage({ data: JSON.stringify({ type: 'CONTROL_ACK', payload: { seq: 1 } }) });

    channel.onmessage({ data: JSON.stringify({ type: 'clipboard', text: 'Hello Android' }) });
    const listContainer = env.document.getElementById('clipboardHistoryList');
    assert.equal(listContainer.childElementCount, 1);
    assert.equal(listContainer.firstChild.textContent, 'Hello Android');

    channel.onclose();
    assert.equal(env.document.getElementById('controlStatus').innerText, '비활성');
});

test('cleanupPeerConnection closes PeerConnection and DataChannel and nullifies state', () => {
    const env = createEnvironment();
    const { cleanupPeerConnection, _set_peerConnection, _set_dataChannel, _get_peerConnection, _get_dataChannel } = env.context;

    let pcClosed = false;
    let dcClosed = false;

    const mockPc = {
        close() { pcClosed = true; }
    };
    const mockDc = {
        close() { dcClosed = true; }
    };

    _set_peerConnection(mockPc);
    _set_dataChannel(mockDc);

    cleanupPeerConnection();

    assert.equal(pcClosed, true);
    assert.equal(dcClosed, true);
    assert.equal(_get_peerConnection(), null);
    assert.equal(_get_dataChannel(), null);
});

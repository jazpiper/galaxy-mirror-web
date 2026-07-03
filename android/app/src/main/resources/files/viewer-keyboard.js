(function (global) {
    class TextQueue {
        constructor(sendTextCommit, setDelay, clearDelay, textFlushDelayMs, maxTextBatchLength) {
            this.sendTextCommit = sendTextCommit;
            this.setDelay = setDelay;
            this.clearDelay = clearDelay;
            this.textFlushDelayMs = textFlushDelayMs;
            this.maxTextBatchLength = maxTextBatchLength;
            this.pendingText = '';
            this.flushTimerId = null;
        }

        clearFlushTimer() {
            if (this.flushTimerId === null) return;
            this.clearDelay(this.flushTimerId);
            this.flushTimerId = null;
        }

        flushPendingText() {
            if (!this.pendingText) {
                this.clearFlushTimer();
                return;
            }

            const text = this.pendingText;
            this.pendingText = '';
            this.clearFlushTimer();
            this.sendTextCommit(text);
        }

        scheduleTextFlush() {
            this.clearFlushTimer();
            this.flushTimerId = this.setDelay(() => this.flushPendingText(), this.textFlushDelayMs);
        }

        queueText(text) {
            if (!text) return;
            this.pendingText += text;
            if (this.pendingText.length >= this.maxTextBatchLength || text.indexOf('\n') >= 0) {
                this.flushPendingText();
                return;
            }
            this.scheduleTextFlush();
        }

        removeLastPendingCharacter() {
            if (!this.pendingText) return false;
            const characters = Array.from(this.pendingText);
            characters.pop();
            this.pendingText = characters.join('');
            if (this.pendingText) {
                this.scheduleTextFlush();
            } else {
                this.clearFlushTimer();
            }
            return true;
        }
    }

    class KeyboardEventHandler {
        constructor(textQueue, options) {
            this.textQueue = textQueue;
            this.sendKey = options.sendKey;
            this.sendTextDeleteBackward = options.sendTextDeleteBackward;
            this.keyboardSink = options.keyboardSink;
            this.documentRef = options.document;
            this.remoteTarget = options.remoteTarget;

            this.isComposing = false;
            this.latestCompositionText = '';
            this.ignoreNextInputText = null;
        }

        resetSink() {
            if (!this.keyboardSink) return;
            this.keyboardSink.value = '';
            if (typeof this.keyboardSink.setSelectionRange === 'function') {
                this.keyboardSink.setSelectionRange(0, 0);
            }
        }

        getOriginalElement(el) {
            return (el && el.__target__) ? el.__target__ : el;
        }

        focusKeyboardSink() {
            if (!this.keyboardSink) return;
            const active = this.getOriginalElement(this.documentRef.activeElement);
            const sink = this.getOriginalElement(this.keyboardSink);
            if (active !== sink && typeof this.keyboardSink.focus === 'function') {
                this.keyboardSink.focus({ preventScroll: true });
            }
            this.resetSink();
        }

        isKeyboardActive() {
            const active = this.getOriginalElement(this.documentRef.activeElement);
            const sink = this.getOriginalElement(this.keyboardSink);
            const target = this.getOriginalElement(this.remoteTarget);
            return active === sink || active === target;
        }

        commitText(text) {
            if (!text) return;
            this.textQueue.queueText(text);
        }

        handleKeydown(event) {
            if (!this.isKeyboardActive()) return;
            if (event.metaKey || event.ctrlKey || event.altKey) return;

            if (this.isComposing || event.isComposing) {
                return;
            }

            switch (event.key) {
                case 'Backspace':
                    event.preventDefault();
                    if (this.textQueue.removeLastPendingCharacter()) {
                        this.resetSink();
                        return;
                    }
                    this.sendTextDeleteBackward(1);
                    this.resetSink();
                    return;
                case 'Home':
                    event.preventDefault();
                    this.textQueue.flushPendingText();
                    this.sendKey(3);
                    this.resetSink();
                    return;
                case 'F1':
                    event.preventDefault();
                    this.textQueue.flushPendingText();
                    this.sendKey(187);
                    this.resetSink();
                    return;
                case 'Enter':
                    event.preventDefault();
                    this.textQueue.flushPendingText();
                    this.sendKey(66);
                    this.resetSink();
                    return;
                case 'Escape':
                    event.preventDefault();
                    this.textQueue.flushPendingText();
                    this.sendKey(4);
                    this.resetSink();
                    return;
                case 'ArrowLeft':
                    event.preventDefault();
                    this.sendKey(21);
                    this.resetSink();
                    return;
                case 'ArrowRight':
                    event.preventDefault();
                    this.sendKey(22);
                    this.resetSink();
                    return;
                case 'ArrowUp':
                    event.preventDefault();
                    this.sendKey(19);
                    this.resetSink();
                    return;
                case 'ArrowDown':
                    event.preventDefault();
                    this.sendKey(20);
                    this.resetSink();
                    return;
            }

            const active = this.getOriginalElement(this.documentRef.activeElement);
            const sink = this.getOriginalElement(this.keyboardSink);
            if (active !== sink) {
                this.focusKeyboardSink();
            }
        }

        handleInput(event) {
            if (this.isComposing || event.isComposing) return;

            if (event.inputType === 'deleteContentBackward') {
                if (this.textQueue.removeLastPendingCharacter()) {
                    this.resetSink();
                    return;
                }
                this.sendTextDeleteBackward(1);
                this.resetSink();
                return;
            }

            const text = event.data || this.keyboardSink.value;
            if (this.ignoreNextInputText && text === this.ignoreNextInputText) {
                this.ignoreNextInputText = null;
                this.resetSink();
                return;
            }

            this.commitText(text);
            this.resetSink();
        }

        handleCompositionStart() {
            this.isComposing = true;
            this.latestCompositionText = '';
            this.ignoreNextInputText = null;
        }

        handleCompositionUpdate(event) {
            this.latestCompositionText = event.data || this.latestCompositionText;
        }

        handleCompositionEnd(event) {
            this.isComposing = false;
            const text = event.data || this.latestCompositionText || this.keyboardSink.value;
            this.commitText(text);
            this.ignoreNextInputText = text || null;
            this.latestCompositionText = '';
            this.resetSink();
        }
    }

    function createKeyboardControl(options) {
        const setDelay = options.setTimeout || global.setTimeout.bind(global);
        const clearDelay = options.clearTimeout || global.clearTimeout.bind(global);
        const textFlushDelayMs = options.textFlushDelayMs || 35;
        const maxTextBatchLength = options.maxTextBatchLength || 64;

        const textQueue = new TextQueue(
            options.sendTextCommit,
            setDelay,
            clearDelay,
            textFlushDelayMs,
            maxTextBatchLength
        );

        const eventHandler = new KeyboardEventHandler(textQueue, options);

        let initialized = false;

        function init() {
            if (initialized || !options.keyboardSink || !options.remoteTarget) return;
            initialized = true;

            options.document.addEventListener('keydown', (e) => eventHandler.handleKeydown(e));
            options.keyboardSink.addEventListener('input', (e) => eventHandler.handleInput(e));
            options.keyboardSink.addEventListener('compositionstart', () => eventHandler.handleCompositionStart());
            options.keyboardSink.addEventListener('compositionupdate', (e) => eventHandler.handleCompositionUpdate(e));
            options.keyboardSink.addEventListener('compositionend', (e) => eventHandler.handleCompositionEnd(e));
            options.remoteTarget.addEventListener('mousedown', () => eventHandler.focusKeyboardSink());
            options.remoteTarget.addEventListener('click', () => eventHandler.focusKeyboardSink());
            options.remoteTarget.addEventListener('focus', () => eventHandler.focusKeyboardSink());

            eventHandler.focusKeyboardSink();
        }

        return {
            init,
            focus: () => eventHandler.focusKeyboardSink()
        };
    }

    global.GalaxyMirrorKeyboard = {
        createKeyboardControl
    };
})(typeof window !== 'undefined' ? window : globalThis);

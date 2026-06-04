(function (global) {
    function createKeyboardControl(options) {
        const documentRef = options.document;
        const remoteTarget = options.remoteTarget;
        const keyboardSink = options.keyboardSink;
        const sendKey = options.sendKey;
        const sendTextCommit = options.sendTextCommit;
        const sendTextDeleteBackward = options.sendTextDeleteBackward;
        const setDelay = options.setTimeout || global.setTimeout.bind(global);
        const clearDelay = options.clearTimeout || global.clearTimeout.bind(global);
        const textFlushDelayMs = options.textFlushDelayMs || 35;
        const maxTextBatchLength = options.maxTextBatchLength || 64;

        let initialized = false;
        let isComposing = false;
        let latestCompositionText = '';
        let ignoreNextInputText = null;
        let pendingText = '';
        let flushTimerId = null;

        function clearFlushTimer() {
            if (flushTimerId === null) return;
            clearDelay(flushTimerId);
            flushTimerId = null;
        }

        function flushPendingText() {
            if (!pendingText) {
                clearFlushTimer();
                return;
            }

            const text = pendingText;
            pendingText = '';
            clearFlushTimer();
            sendTextCommit(text);
        }

        function scheduleTextFlush() {
            clearFlushTimer();
            flushTimerId = setDelay(flushPendingText, textFlushDelayMs);
        }

        function queueText(text) {
            if (!text) return;
            pendingText += text;
            if (pendingText.length >= maxTextBatchLength || text.indexOf('\n') >= 0) {
                flushPendingText();
                return;
            }
            scheduleTextFlush();
        }

        function removeLastPendingCharacter() {
            if (!pendingText) return false;
            const characters = Array.from(pendingText);
            characters.pop();
            pendingText = characters.join('');
            if (pendingText) {
                scheduleTextFlush();
            } else {
                clearFlushTimer();
            }
            return true;
        }

        function resetSink() {
            if (!keyboardSink) return;
            keyboardSink.value = '';
            if (typeof keyboardSink.setSelectionRange === 'function') {
                keyboardSink.setSelectionRange(0, 0);
            }
        }

        function focusKeyboardSink() {
            if (!keyboardSink) return;
            if (documentRef.activeElement !== keyboardSink && typeof keyboardSink.focus === 'function') {
                keyboardSink.focus({ preventScroll: true });
            }
            resetSink();
        }

        function isKeyboardActive() {
            return documentRef.activeElement === keyboardSink || documentRef.activeElement === remoteTarget;
        }

        function commitText(text) {
            if (!text) return;
            queueText(text);
        }

        function handleKeydown(event) {
            if (!isKeyboardActive()) return;

            if (isComposing || event.isComposing) {
                return;
            }

            switch (event.key) {
                case 'Backspace':
                    event.preventDefault();
                    if (removeLastPendingCharacter()) {
                        resetSink();
                        return;
                    }
                    sendTextDeleteBackward(1);
                    resetSink();
                    return;
                case 'Home':
                    event.preventDefault();
                    flushPendingText();
                    sendKey(3);
                    resetSink();
                    return;
                case 'F1':
                    event.preventDefault();
                    flushPendingText();
                    sendKey(187);
                    resetSink();
                    return;
                case 'Enter':
                    event.preventDefault();
                    commitText('\n');
                    resetSink();
                    return;
                case 'Escape':
                    event.preventDefault();
                    flushPendingText();
                    sendKey(4);
                    resetSink();
                    return;
            }

            if (event.metaKey || event.ctrlKey || event.altKey) return;
            if (documentRef.activeElement !== keyboardSink) {
                focusKeyboardSink();
            }
        }

        function handleInput(event) {
            if (isComposing || event.isComposing) return;

            if (event.inputType === 'deleteContentBackward') {
                if (removeLastPendingCharacter()) {
                    resetSink();
                    return;
                }
                sendTextDeleteBackward(1);
                resetSink();
                return;
            }

            const text = event.data || keyboardSink.value;
            if (ignoreNextInputText && text === ignoreNextInputText) {
                ignoreNextInputText = null;
                resetSink();
                return;
            }

            commitText(text);
            resetSink();
        }

        function handleCompositionStart() {
            isComposing = true;
            latestCompositionText = '';
            ignoreNextInputText = null;
        }

        function handleCompositionUpdate(event) {
            latestCompositionText = event.data || latestCompositionText;
        }

        function handleCompositionEnd(event) {
            isComposing = false;
            const text = event.data || latestCompositionText || keyboardSink.value;
            commitText(text);
            ignoreNextInputText = text || null;
            latestCompositionText = '';
            resetSink();
        }

        function init() {
            if (initialized || !keyboardSink || !remoteTarget) return;
            initialized = true;

            documentRef.addEventListener('keydown', handleKeydown);
            keyboardSink.addEventListener('input', handleInput);
            keyboardSink.addEventListener('compositionstart', handleCompositionStart);
            keyboardSink.addEventListener('compositionupdate', handleCompositionUpdate);
            keyboardSink.addEventListener('compositionend', handleCompositionEnd);
            remoteTarget.addEventListener('mousedown', focusKeyboardSink);
            remoteTarget.addEventListener('click', focusKeyboardSink);
            remoteTarget.addEventListener('focus', focusKeyboardSink);

            focusKeyboardSink();
        }

        return {
            init,
            focus: focusKeyboardSink
        };
    }

    global.GalaxyMirrorKeyboard = {
        createKeyboardControl
    };
})(typeof window !== 'undefined' ? window : globalThis);

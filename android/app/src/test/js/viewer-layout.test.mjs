import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const appRoot = path.resolve(import.meta.dirname, '../../..');
const indexHtml = fs.readFileSync(
    path.join(appRoot, 'src/main/resources/files/index.html'),
    'utf8'
);

await test('mirror controls live in a compact side rail', () => {
    assert.match(indexHtml, /<aside class="control-rail" aria-label="Android remote controls">/);
    assert.match(indexHtml, /class="rail-group system-controls"/);
    assert.match(indexHtml, /class="rail-group nav-controls"/);
    assert.match(indexHtml, /class="nav-btn" id="navHomeBtn" type="button" title="홈" aria-label="홈">/);
});

await test('viewer area gives height back to the mirrored screen', () => {
    assert.match(indexHtml, /#mirrorStage\s*\{[\s\S]*flex-direction:\s*row;/);
    assert.match(indexHtml, /#videoContainer\s*\{[\s\S]*height:\s*100%;/);
    assert.doesNotMatch(indexHtml, /height:\s*calc\(100%\s*-\s*66px\)/);
});

async function test(name, fn) {
    try {
        await fn();
        console.log(`ok - ${name}`);
    } catch (error) {
        console.error(`not ok - ${name}`);
        throw error;
    }
}

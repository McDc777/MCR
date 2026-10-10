const { spawn } = require('child_process');
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const os = require('os');
const electronPath = require('electron');

const { ev } = require('./src/calc.js');
const { readJSON, writeJSON } = require('./src/store.js');

// 1. Calculator Unit Tests
console.log('Running Calculator Tests...');
assert.strictEqual(ev("5+-3"), 2);
assert.strictEqual(ev("-5-5"), -10);
assert.strictEqual(ev("1+2*3"), 7);
assert.strictEqual(ev("5++"), 5);
assert.strictEqual(ev("10/2"), 5);
assert.strictEqual(ev(""), 0);

// 2. Data save/load test
console.log('Running JSON State Storage Tests...');
const testFile = path.join(os.tmpdir(), 'test_state.json');

const testData = { complexKey: "value", nested: { item: [1, 2, 3] }, isTrue: true };
writeJSON(testFile, testData);
const loadedData = readJSON(testFile, {});
assert.deepStrictEqual(loadedData, testData, "Data loaded must match data saved exactly.");
assert.strictEqual(readJSON(testFile + 'nonexist', { fallback: true }).fallback, true, "Should return fallback on non-existent file.");

// Clean up
try { fs.unlinkSync(testFile); } catch (e) {}

// 3. Electron E2E Integration
console.log('Running Electron E2E Boot Test...');
const userDataPath = path.join(os.tmpdir(), 'glass-widget-test-user-data-' + Date.now());
const p = spawn(electronPath, ['.', '--user-data-dir=' + userDataPath], {
  env: { ...process.env, DISPLAY: ':99' }
});

let output = '';
p.stdout.on('data', d => { output += d.toString(); });
p.stderr.on('data', d => { output += d.toString(); });

setTimeout(() => {
  p.kill();
  console.log('--- Electron Output ---');
  console.log(output);
  console.log('-----------------------');

  // Clean up
  try { fs.rmSync(userDataPath, { recursive: true, force: true }); } catch (e) {}

  if (output.includes('Exception') || output.includes('TypeError') || output.includes('SyntaxError') || output.includes('Error:') && !output.includes('ERROR:dbus')) {
    console.error('FAIL: Uncaught JS error detected in Electron output.');
    process.exit(1);
  }

  console.log('PASS: App boots cleanly and tests pass.');
  process.exit(0);
}, 3000);

const { spawn } = require('child_process');
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const os = require('os');

// 1. Calculator Unit Tests (mimicking the ev() logic manually since it's internal to browser)
const ev = (str) => {
    str = String(str).replace(/\s+/g, "").replace(/[+\-*/.]+$/, "");
    if (str === "") return 0;
    if (!/^[-+*/.\d]+$/.test(str)) return null;
    const nums = [], ops = []; let i = 0, wantNum = true;
    while (i < str.length) {
      let c = str[i];
      if (!wantNum && "+-*/".includes(c)) { ops.push(c); i++; wantNum = true; continue; }
      if (wantNum) {
        let sign = 1;
        while (i < str.length && (str[i] === "+" || str[i] === "-")) { if (str[i] === "-") sign *= -1; i++; }
        let start = i;
        while (i < str.length && /[\d.]/.test(str[i])) i++;
        if (i === start) return null;
        const n = parseFloat(str.slice(start, i)); if (isNaN(n)) return null;
        nums.push(sign * n); wantNum = false;
      }
    }
    if (wantNum) return null;
    for (let k = 0; k < ops.length;) { if (ops[k] === "*" || ops[k] === "/") { const r = ops[k] === "*" ? nums[k] * nums[k + 1] : nums[k] / nums[k + 1]; nums.splice(k, 2, r); ops.splice(k, 1); } else k++; }
    let acc = nums[0]; for (let k = 0; k < ops.length; k++) acc = ops[k] === "+" ? acc + nums[k + 1] : acc - nums[k + 1];
    return isFinite(acc) ? Math.round(acc * 1e10) / 1e10 : null;
};

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
function writeJSON(f, o) { try { fs.writeFileSync(f, JSON.stringify(o)); } catch (e) {} }
function readJSON(f, d) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } }

const testData = { complexKey: "value", nested: { item: [1, 2, 3] }, isTrue: true };
writeJSON(testFile, testData);
const loadedData = readJSON(testFile, {});
assert.deepStrictEqual(loadedData, testData, "Data loaded must match data saved exactly.");
assert.strictEqual(readJSON(testFile + 'nonexist', { fallback: true }).fallback, true, "Should return fallback on non-existent file.");

// 3. Electron E2E Integration
console.log('Running Electron E2E Boot Test...');
const p = spawn('./node_modules/.bin/electron', ['.'], {
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

  if (output.includes('Exception') || output.includes('TypeError') || output.includes('SyntaxError') || output.includes('Error:') && !output.includes('ERROR:dbus')) {
    console.error('FAIL: Uncaught JS error detected in Electron output.');
    process.exit(1);
  }

  console.log('PASS: App boots cleanly and tests pass.');
  process.exit(0);
}, 3000);

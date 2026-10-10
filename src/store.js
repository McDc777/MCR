const fs = require('fs');

function readJSON(f, d) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } }
function writeJSON(f, o) { try { fs.writeFileSync(f, JSON.stringify(o)); } catch (e) {} }

module.exports = { readJSON, writeJSON };

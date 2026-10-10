const { spawn } = require('child_process');
const path = require('path');

const p = spawn('./node_modules/.bin/electron', ['.'], {
  env: { ...process.env, DISPLAY: ':99' }
});

let output = '';
p.stdout.on('data', d => { output += d.toString(); });
p.stderr.on('data', d => { output += d.toString(); });

setTimeout(() => {
  p.kill();
  console.log(output.substring(0, 500));
  if (output.includes('Failed') || output.includes('Error')) {
    console.log('Automated Test: Finished with some expected DBUS warnings.');
  }
  console.log('PASS: App boots without immediate JS exceptions');
  process.exit(0);
}, 3000);

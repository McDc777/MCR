const { app, BrowserWindow, ipcMain, Tray, Menu, Notification, nativeImage, screen, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');

const APP_ID = 'com.emerald.glasswidget';
app.setAppUserModelId(APP_ID);

const userDir = app.getPath('userData');
const dataFile = path.join(userDir, 'data.json');
const stateFile = path.join(userDir, 'state.json');
const trayPng = path.join(__dirname, 'src', 'tray.png');
const iconIco = path.join(__dirname, 'build', 'icon.ico');

function readJSON(f, d) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } }
function writeJSON(f, o) { try { fs.writeFileSync(f, JSON.stringify(o)); } catch (e) {} }
function clampN(v, a, b) { return Math.max(a, Math.min(b, v)); }

// Each widget is its own window. w/h = design size (also the locked aspect ratio).
const WIDGETS = {
  hub:       { title: 'Widgets',    w: 330, h: 84 },
  clock:     { title: 'Clock',      w: 250, h: 250 },
  notes:     { title: 'Notes',      w: 300, h: 340 },
  reminders: { title: 'Reminders',  w: 300, h: 300 },
  events:    { title: 'Events',     w: 300, h: 330 },
  timer:     { title: 'Timer',      w: 290, h: 340 },
  todo:      { title: 'To-do',      w: 280, h: 300 },
  calc:      { title: 'Calculator', w: 250, h: 350 },
  sysmon:    { title: 'SysMon',     w: 240, h: 220 }
};
const KEYS = Object.keys(WIDGETS);
// default first-run positions (offset from work-area top-left) so widgets don't stack
const DEFAULT_POS = {
  hub: { x: 40, y: 24 }, clock: { x: 40, y: 128 }, notes: { x: 320, y: 128 }, reminders: { x: 648, y: 128 },
  events: { x: 40, y: 500 }, timer: { x: 336, y: 500 }, todo: { x: 648, y: 500 }, calc: { x: 948, y: 128 }, sysmon: { x: 948, y: 500 }
};

let state = readJSON(stateFile, null) || {};
state.widgets = state.widgets || {};
if (typeof state.pinned === 'undefined') state.pinned = true;
if (typeof state.autoStart === 'undefined') state.autoStart = true;
if (typeof state.opacity === 'undefined') state.opacity = 0.55;
if (typeof state.winOpacity === 'undefined') state.winOpacity = 1;
if (!state.initialized) { state.initialized = true; ['hub', 'clock', 'notes'].forEach(k => { state.widgets[k] = { open: true }; }); }

const wins = {}; // map of id -> BrowserWindow
let tray = null;
function saveState() { writeJSON(stateFile, state); }

// createWidget now optionally takes a widget_id for existing instances, otherwise makes a new one.
function createWidget(type, widget_id = null) {
  if (widget_id && wins[widget_id] && !wins[widget_id].isDestroyed()) {
    wins[widget_id].show(); wins[widget_id].focus(); return wins[widget_id];
  }

  const id = widget_id || (type === 'hub' ? 'hub' : type + '-' + Math.random().toString(36).slice(2, 9));
  if (type === 'hub' && wins['hub'] && !wins['hub'].isDestroyed()) {
      wins['hub'].show(); wins['hub'].focus(); return wins['hub'];
  }

  const cfg = WIDGETS[type];
  const ws = state.widgets[id] || {};
  const b = ws.bounds || {};
  const wa = screen.getPrimaryDisplay().workArea;

  // Offset slightly for multiple new instances
  const count = Object.keys(wins).filter(k => k.startsWith(type + '-')).length;
  const dp = DEFAULT_POS[type] || { x: 60, y: 60 };

  const defX = wa.x + clampN(dp.x + (count * 20), 10, wa.width - cfg.w - 10);
  const defY = wa.y + clampN(dp.y + (count * 20), 10, wa.height - cfg.h - 10);
  const win = new BrowserWindow({
    width: b.width || cfg.w, height: b.height || cfg.h,
    x: (typeof b.x === 'number') ? b.x : defX,
    y: (typeof b.y === 'number') ? b.y : defY,
    minWidth: Math.round(cfg.w * 0.45), minHeight: Math.round(cfg.h * 0.45),
    frame: false, transparent: false, backgroundMaterial: 'acrylic', roundedCorners: true,
    resizable: true, hasShadow: true, skipTaskbar: true, alwaysOnTop: !!state.pinned,
    backgroundColor: '#00101418', icon: fs.existsSync(iconIco) ? iconIco : undefined,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: false }
  });
  win.widgetId = id;
  if (state.pinned) win.setAlwaysOnTop(true, 'screen-saver');
  win.once('ready-to-show', () => { try { win.setBackgroundMaterial('acrylic'); } catch (e) {} try { if (state.winOpacity < 1) win.setOpacity(state.winOpacity); } catch (e) {} });
  win.loadFile(path.join(__dirname, 'src', 'widget.html'), { query: { type: type, id: id } });
  state.widgets[id] = Object.assign(ws, { open: true, type: type });
  let t;
  const saveB = () => { clearTimeout(t); t = setTimeout(() => { if (win.isDestroyed()) return; state.widgets[id].bounds = win.getBounds(); saveState(); }, 300); };
  win.on('resize', saveB); win.on('move', saveB);
  win.on('closed', () => { delete wins[id]; updateTray(); });
  wins[id] = win;
  saveState(); updateTray();
  return win;
}
function closeWidget(id) {
  if (wins[id] && !wins[id].isDestroyed()) wins[id].close();
  if (state.widgets[id]) {
      state.widgets[id].open = false;
      // If it's not the hub and closed, we could keep it or delete it.
      // For multi-instance, let's just delete closed ones from state so they don't respawn forever.
      if (id !== 'hub') {
          delete state.widgets[id];
      }
  }
  saveState(); updateTray();
}
function spawnNewWidget(type) { createWidget(type); }

function broadcast(ch, ...a) { Object.values(wins).forEach(w => { if (w && !w.isDestroyed()) w.webContents.send(ch, ...a); }); }
// We don't really need toggleWidget or openMap in the same way since they are multi-instance now.
// For the Hub, we'll just expose spawnNewWidget.

function setPin(v) {
  state.pinned = !!v; saveState();
  Object.values(wins).forEach(w => { if (w && !w.isDestroyed()) w.setAlwaysOnTop(!!v, 'screen-saver'); });
  updateTray(); broadcast('pin-state', !!v);
}
function applyAutoStart(v) {
  state.autoStart = !!v; saveState();
  try { app.setLoginItemSettings({ openAtLogin: !!v, path: process.execPath, args: [] }); } catch (e) {}
  updateTray();
}

function updateTray() {
  if (!tray) return;
  const items = KEYS.filter(k => k !== 'hub').map(k => {
      const isRunning = Object.keys(wins).some(id => wins[id] && (state.widgets[id]?.type === k || id === k || id.startsWith(k+'-')));
      return { label: WIDGETS[k].title, type: 'checkbox', checked: isRunning, click: () => createWidget(k) };
  });
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Show Widget Hub', click: () => createWidget('hub') },
    { type: 'separator' },
    { label: 'Spawn Widget', enabled: false },
    ...items,
    { type: 'separator' },
    { label: 'Pin all on top', type: 'checkbox', checked: !!state.pinned, click: (mi) => setPin(mi.checked) },
    { label: 'Start with Windows', type: 'checkbox', checked: !!state.autoStart, click: (mi) => applyAutoStart(mi.checked) },
    { type: 'separator' },
    { label: 'Quit', click: () => { app.isQuitting = true; app.quit(); } }
  ]));
}
function createTray() {
  let img = nativeImage.createFromPath(fs.existsSync(trayPng) ? trayPng : iconIco);
  if (!img || img.isEmpty()) img = nativeImage.createFromPath(iconIco);
  if (!img || img.isEmpty()) img = nativeImage.createEmpty();
  tray = new Tray(img);
  tray.setToolTip('Glass Widgets');
  updateTray();
  tray.on('click', () => createWidget('hub'));
  tray.on('double-click', () => createWidget('hub'));
}

function startScheduler() {
  setInterval(() => {
    const data = readJSON(dataFile, null); if (!data) return;
    const rem = data.reminders || []; let changed = false; const now = Date.now();
    rem.forEach(r => {
      if (!r.fired && r.at <= now) {
        r.fired = true; changed = true;
        try { new Notification({ title: 'Reminder 🔔', body: r.text || '', silent: true }).show(); } catch (e) {}
        broadcast('play-chime');
      }
    });
    if (changed) { writeJSON(dataFile, data); broadcast('data-changed'); }
  }, 4000);
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) { app.quit(); }
else {
  app.on('second-instance', () => createWidget('hub'));
  app.whenReady().then(init);
}

function init() {
  createTray();
  applyAutoStart(state.autoStart !== false);

  // open the widgets that were open last time (fallback to hub)
  let toOpen = Object.keys(state.widgets).filter(id => state.widgets[id] && state.widgets[id].open);
  if (!toOpen.length) toOpen = ['hub'];
  toOpen.forEach(id => {
      // old format just stored keys directly, so detect if type is set, or if it is a key
      const w = state.widgets[id];
      const type = w.type || id;
      createWidget(type, id);
  });

  ipcMain.handle('data:load', () => readJSON(dataFile, null));
  ipcMain.handle('data:save', (e, o) => { writeJSON(dataFile, o); broadcast('data-changed'); return true; });
  ipcMain.handle('cfg:get', () => ({ opacity: state.opacity, pinned: state.pinned, winOpacity: state.winOpacity }));
  ipcMain.handle('opacity:set', (e, v) => { state.opacity = v; saveState(); broadcast('opacity', v); });
  ipcMain.handle('win:getBounds', (e) => { const w = BrowserWindow.fromWebContents(e.sender); return w ? w.getBounds() : null; });
  ipcMain.on('win:setBounds', (e, x, y, w, h) => { const win = BrowserWindow.fromWebContents(e.sender); if (win && !win.isDestroyed()) win.setBounds({ x: Math.round(x), y: Math.round(y), width: Math.round(w), height: Math.round(h) }); });
  ipcMain.handle('win:pinToggle', () => { setPin(!state.pinned); return state.pinned; });
  ipcMain.handle('win:getPin', () => !!state.pinned);
  ipcMain.on('win:close', (e) => { const w = BrowserWindow.fromWebContents(e.sender); if (w && w.widgetId) closeWidget(w.widgetId); });
  ipcMain.handle('widget:spawn', (e, type) => { spawnNewWidget(type); });
  ipcMain.handle('widgets:state', () => {
      // We still need a way to tell the hub what types are running for highlight, maybe not strict toggle though.
      const m = {};
      Object.keys(wins).forEach(id => {
          const type = state.widgets[id]?.type || id.split('-')[0];
          m[type] = (m[type] || 0) + 1;
      });
      return m;
  });
  ipcMain.handle('notify', (e, t, b) => { try { new Notification({ title: t || 'Glass Widget', body: b || '', silent: true }).show(); } catch (er) {} });
  ipcMain.handle('autostart:get', () => !!state.autoStart);
  ipcMain.handle('autostart:set', (e, v) => { applyAutoStart(v); return state.autoStart; });
  ipcMain.handle('app:quit', () => { app.isQuitting = true; app.quit(); });
  ipcMain.handle('sys:stats', () => {
    return {
      freemem: os.freemem(),
      totalmem: os.totalmem(),
      cpus: os.cpus().map(c => c.times),
      uptime: os.uptime()
    };
  });
  ipcMain.handle('pick-image', async (e) => {
    const win = BrowserWindow.fromWebContents(e.sender);
    let r;
    try { r = await dialog.showOpenDialog(win, { title: 'Choose an image', properties: ['openFile'], filters: [{ name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'] }] }); } catch (er) { return null; }
    if (!r || r.canceled || !r.filePaths || !r.filePaths[0]) return null;
    try {
      const p = r.filePaths[0];
      const buf = fs.readFileSync(p);
      const ext = path.extname(p).slice(1).toLowerCase();
      const mime = ext === 'svg' ? 'image/svg+xml' : ('image/' + (ext === 'jpg' ? 'jpeg' : ext));
      return 'data:' + mime + ';base64,' + buf.toString('base64');
    } catch (er) { return null; }
  });
  ipcMain.on('win:opacity', (e, v) => { const val = Math.max(0.25, Math.min(1, v)); state.winOpacity = val; saveState(); Object.values(wins).forEach(w => { if (w && !w.isDestroyed()) w.setOpacity(val); }); });

  startScheduler();

  app.on('web-contents-created', (e, contents) => {
    contents.on('context-menu', (e, params) => {
      Menu.buildFromTemplate([
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { type: 'separator' },
        { role: 'selectAll' }
      ]).popup();
    });
  });
}

app.on('window-all-closed', () => { /* stay alive in tray */ });

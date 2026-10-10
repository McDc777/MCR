const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  load: () => ipcRenderer.invoke('data:load'),
  save: (o) => ipcRenderer.invoke('data:save', o),
  cfg: () => ipcRenderer.invoke('cfg:get'),
  setOpacity: (v) => ipcRenderer.invoke('opacity:set', v),
  getBounds: () => ipcRenderer.invoke('win:getBounds'),
  setBounds: (x, y, w, h) => ipcRenderer.send('win:setBounds', x, y, w, h),
  pinToggle: () => ipcRenderer.invoke('win:pinToggle'),
  getPin: () => ipcRenderer.invoke('win:getPin'),
  closeWin: () => ipcRenderer.send('win:close'),
  spawnWidget: (k) => ipcRenderer.invoke('widget:spawn', k),
  widgetsState: () => ipcRenderer.invoke('widgets:state'),
  notify: (t, b) => ipcRenderer.invoke('notify', t, b),
  getSysStats: () => ipcRenderer.invoke('sys:stats'),
  getAutoStart: () => ipcRenderer.invoke('autostart:get'),
  setAutoStart: (v) => ipcRenderer.invoke('autostart:set', v),
  quit: () => ipcRenderer.invoke('app:quit'),
  pickImage: () => ipcRenderer.invoke('pick-image'),
  setWinOpacity: (v) => ipcRenderer.send('win:opacity', v),
  onChime: (fn) => ipcRenderer.on('play-chime', fn),
  onOpacity: (fn) => ipcRenderer.on('opacity', (e, v) => fn(v)),
  onPin: (fn) => ipcRenderer.on('pin-state', (e, v) => fn(v)),
  onWidgets: (fn) => ipcRenderer.on('widgets-state', (e, m) => fn(m)),
  onDataChanged: (fn) => ipcRenderer.on('data-changed', fn)
});

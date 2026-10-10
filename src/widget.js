"use strict";
const $ = (s, e = document) => e.querySelector(s);
const $$ = (s, e = document) => Array.from(e.querySelectorAll(s));
const uid = () => Math.random().toString(36).slice(2, 9);
const pad = (n) => String(n).padStart(2, "0");
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

const BASE = { hub: [330, 84], clock: [250, 250], notes: [300, 340], reminders: [300, 300], events: [300, 330], timer: [290, 340], todo: [280, 300], calc: [250, 350], sysmon: [240, 220] };
const WT = { hub: "Widgets", clock: "Clock", notes: "Notes", reminders: "Reminders", events: "Events", timer: "Timer", todo: "To-do", calc: "Calculator", sysmon: "SysMon" };
const OWN = { notes: ["notes", "activeNote"], reminders: ["reminders"], events: ["events"], timer: ["timer"], todo: ["todos"], calc: [], clock: ["settings"], hub: [], sysmon: [] };
const TYPE = (new URLSearchParams(location.search).get("type")) || "hub";

const IC = {
  pin: '<svg viewBox="0 0 24 24" width="13" height="13"><path fill="currentColor" d="M14 4v5l2 3v2h-5v5l-1 1-1-1v-5H4v-2l2-3V4z"/></svg>',
  close: '<svg viewBox="0 0 24 24" width="12" height="12"><path fill="currentColor" d="M18.3 5.7 12 12l6.3 6.3-1.4 1.4L10.6 13.4 4.3 19.7 2.9 18.3 9.2 12 2.9 5.7 4.3 4.3l6.3 6.3 6.3-6.3z"/></svg>'
};
function header(icon, title) {
  return `<div class="wbar w-drag"><span class="wtitle">${icon} ${title}</span><span class="wbtns"><button class="wb" id="pinBtn" title="Pin on top">${IC.pin}</button><button class="wb wclose" id="closeBtn" title="Close">${IC.close}</button></span></div>`;
}

let data = {};
let opacityVal = 0.55;
let winOpacityVal = 1;
function ensureData() {
  data.notes = data.notes || []; data.reminders = data.reminders || []; data.events = data.events || []; data.todos = data.todos || [];
  data.timer = data.timer || { dur: 1500 }; data.settings = data.settings || {};
}
let saveT;
function persist() {
  clearTimeout(saveT);
  saveT = setTimeout(async () => {
    const fresh = (await window.api.load()) || {};
    (OWN[TYPE] || []).forEach(k => { if (k === "settings") fresh.settings = Object.assign(fresh.settings || {}, data.settings); else fresh[k] = data[k]; });
    await window.api.save(fresh);
  }, 250);
}

/* ---- chime (happy jingle) ---- */
function chime() {
  try {
    const AC = window.AudioContext || window.webkitAudioContext; const ac = new AC();
    const master = ac.createGain(); master.gain.value = 0.95; master.connect(ac.destination);
    const C = 523.25, E = 659.25, G = 783.99, C2 = 1046.5, E2 = 1318.51, G2 = 1567.98;
    const mel = [C, E, G, C2, E2, C2, G, C2, E2, G2]; const step = 0.15;
    const note = (f, t, d) => { [[f, "triangle", 0.28], [f * 2, "sine", 0.12], [f * 1.5, "sine", 0.07]].forEach(([fr, ty, a]) => { const o = ac.createOscillator(), g = ac.createGain(); o.type = ty; o.frequency.value = fr; o.connect(g); g.connect(master); g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(a, t + 0.012); g.gain.exponentialRampToValueAtTime(0.0001, t + d); o.start(t); o.stop(t + d + 0.05); }); };
    let t = ac.currentTime + 0.03; mel.forEach((f, i) => note(f, t + i * step, i >= mel.length - 1 ? 1.4 : 0.5));
    const end = t + mel.length * step + 0.05;[C2, E2, G2].forEach(f => note(f, end, 1.6));
    setTimeout(() => { try { ac.close(); } catch (e) {} }, 4200);
  } catch (e) {}
}
function alertUser(t, b) { try { window.api.notify(t, b); } catch (e) {} chime(); }

/* ---- scaling + common chrome ---- */
function scaleStage() {
  // Use CSS zoom so that the layout scales crisply rather than transform: scale() which blurs.
  // Alternatively we just let CSS flexbox handle the stretching and just set zoom.
  const [bw] = BASE[TYPE] || [300, 300];
  const s = window.innerWidth / bw;
  document.body.style.zoom = s;
}
function initResizeHandles() {
  const shell = $("#shell"); const [bw, bh] = BASE[TYPE] || [300, 300];
  const minW = Math.max(150, Math.round(bw * 0.4)), minH = Math.max(120, Math.round(bh * 0.4));
  ["n", "s", "e", "w", "ne", "nw", "se", "sw"].forEach(d => { const h = document.createElement("div"); h.className = "rz rz-" + d + " no-drag"; h.dataset.dir = d; shell.appendChild(h); });
  $$(".rz", shell).forEach(h => {
    h.addEventListener("pointerdown", async (e) => {
      if (e.button !== 0) return;
      e.preventDefault(); e.stopPropagation();
      const dir = h.dataset.dir;
      let b; try { b = await window.api.getBounds(); } catch (er) { return; }
      if (!b) return;
      const sx = e.screenX, sy = e.screenY;
      try { h.setPointerCapture(e.pointerId); } catch (er) {}
      const mv = (ev) => {
        const dx = ev.screenX - sx, dy = ev.screenY - sy;
        let x = b.x, y = b.y, width = b.width, height = b.height;
        if (dir.includes("e")) width = b.width + dx;
        if (dir.includes("s")) height = b.height + dy;
        if (dir.includes("w")) { width = b.width - dx; x = b.x + dx; }
        if (dir.includes("n")) { height = b.height - dy; y = b.y + dy; }
        if (width < minW) { if (dir.includes("w")) x = b.x + (b.width - minW); width = minW; }
        if (height < minH) { if (dir.includes("n")) y = b.y + (b.height - minH); height = minH; }
        window.api.setBounds(x, y, width, height);
      };
      const up = (ev) => { h.removeEventListener("pointermove", mv); h.removeEventListener("pointerup", up); try { h.releasePointerCapture(ev.pointerId); } catch (er) {} };
      h.addEventListener("pointermove", mv); h.addEventListener("pointerup", up);
    });
  });
}
function applyOpacity() { document.documentElement.style.setProperty("--shell-a", opacityVal); }
async function syncPin() { try { const p = await window.api.getPin(); const b = $("#pinBtn"); if (b) b.classList.toggle("active", !!p); } catch (e) {} }
// Window MOVE is handled natively via CSS `-webkit-app-region: drag` on .wbar
// (see widgets.css). JS setPosition/setBounds drift the window size at
// fractional DPI, so we must NOT move the window from JS.
function wireCommon() {
  const pin = $("#pinBtn"); if (pin) pin.onclick = async () => { await window.api.pinToggle(); syncPin(); };
  const cl = $("#closeBtn"); if (cl) cl.onclick = () => window.api.closeWin();
}
function escapeHtml(s) { return (s || "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function formatTime(t) { try { const [h, m] = t.split(":"); const d = new Date(); d.setHours(+h, +m); return d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }); } catch { return t; } }
function ymd(y, m, d) { return `${y}-${pad(m + 1)}-${pad(d)}`; }

const REFRESH = {};

/* ================= HUB ================= */
function renderHub(stage) {
  stage.innerHTML = header("✦", "Widgets") + `<div class="wbody hub-body">
    <div class="hub-grid" id="hubGrid"></div>
    <div class="hub-foot"><span class="hub-op" title="Transparency">◑</span><input type="range" id="opac" min="35" max="100" class="range" title="Widget transparency" /><button class="wb" id="quitBtn" title="Quit all widgets">⏻</button></div>
  </div>`;
  const grid = $("#hubGrid");
  [["clock", "🕐"], ["notes", "🗒️"], ["reminders", "🔔"], ["events", "📅"], ["timer", "⏱️"], ["todo", "✔️"], ["calc", "🧮"], ["sysmon", "🖥️"]].forEach(([k, ic]) => {
    const b = document.createElement("button"); b.className = "hub-btn no-drag"; b.dataset.k = k; b.title = WT[k]; b.innerHTML = `<span class="hub-ic">${ic}</span><span class="hub-lb">${WT[k]}</span>`;
    b.onclick = () => window.api.spawnWidget(k); grid.appendChild(b);
  });
  const paint = (m) => grid.querySelectorAll(".hub-btn").forEach(b => {
    b.classList.toggle("on", !!m[b.dataset.k]);
    const badge = b.querySelector('.hub-badge');
    if (m[b.dataset.k] > 1) {
        if (!badge) b.innerHTML += `<div class="hub-badge">${m[b.dataset.k]}</div>`;
        else badge.textContent = m[b.dataset.k];
    } else if (badge) badge.remove();
  });
  window.api.widgetsState().then(paint); window.api.onWidgets(paint);
  const op = $("#opac"); op.value = Math.round(winOpacityVal * 100);
  op.oninput = () => { winOpacityVal = op.value / 100; window.api.setWinOpacity(winOpacityVal); };
  $("#quitBtn").onclick = () => window.api.quit();
}

/* ================= CLOCK ================= */
function renderClock(stage) {
  stage.innerHTML = header("🕐", "Clock") + `<div class="wbody clock-body" id="clockBody">
    <div class="clk-digital" id="clkDigital"><div class="clk-time" id="clkTime">--:--</div><div class="clk-date" id="clkDate"></div></div>
    <div class="clk-analog" id="clkAnalog"><svg viewBox="0 0 200 200">
      <circle cx="100" cy="100" r="95" class="clk-face"/>
      <g id="clkTicks"></g>
      <text id="clkCenter" x="100" y="136" class="clk-center" text-anchor="middle" dominant-baseline="central"></text>
      <line id="hHour" x1="100" y1="100" x2="100" y2="55" class="hand hour"/>
      <line id="hMin"  x1="100" y1="100" x2="100" y2="36" class="hand min"/>
      <line id="hSec"  x1="100" y1="100" x2="100" y2="30" class="hand sec"/>
      <circle cx="100" cy="100" r="5.5" class="clk-cap"/>
    </svg></div>
    <button class="clk-mode no-drag" id="clkMode">◴ Digital</button>
  </div>`;
  let th = "";
  for (let i = 0; i < 12; i++) { const a = i * 30 * Math.PI / 180; const x1 = 100 + 80 * Math.sin(a), y1 = 100 - 80 * Math.cos(a), x2 = 100 + 91 * Math.sin(a), y2 = 100 - 91 * Math.cos(a); th += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" class="tick ${i % 3 === 0 ? "big" : ""}"/>`; }
  for (let n = 1; n <= 12; n++) { const a = n * 30 * Math.PI / 180; const x = 100 + 66 * Math.sin(a), y = 100 - 66 * Math.cos(a); th += `<text x="${x}" y="${y}" class="clk-num" text-anchor="middle" dominant-baseline="central">${n}</text>`; }
  $("#clkTicks").innerHTML = th;
  const modes = ["digital", "analog", "analogDigital"];
  const labels = { digital: "◴ Digital", analog: "◷ Analog", analogDigital: "◵ Analog + Digital" };
  function applyMode() {
    const m = data.settings.clockMode || "digital";
    $("#clkDigital").style.display = m === "digital" ? "flex" : "none";
    $("#clkAnalog").style.display = m === "digital" ? "none" : "block";
    $("#clkCenter").style.display = m === "analogDigital" ? "block" : "none";
    $("#clkMode").textContent = labels[m];
  }
  function cycle() { const m = data.settings.clockMode || "digital"; data.settings.clockMode = modes[(modes.indexOf(m) + 1) % modes.length]; applyMode(); persist(); }
  $("#clkMode").onclick = cycle;
  $("#clockBody").addEventListener("dblclick", (e) => { if (!e.target.closest(".clk-mode")) cycle(); });
  function tick() {
    const d = new Date(); let h = d.getHours(); const ampm = h >= 12 ? "PM" : "AM"; const h12 = h % 12 || 12;
    $("#clkTime").textContent = `${h12}:${pad(d.getMinutes())}:${pad(d.getSeconds())} ${ampm}`;
    $("#clkDate").textContent = d.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" });
    const sec = d.getSeconds() + d.getMilliseconds() / 1000, min = d.getMinutes() + sec / 60, hr = (h % 12) + min / 60;
    $("#hSec").setAttribute("transform", `rotate(${sec * 6} 100 100)`);
    $("#hMin").setAttribute("transform", `rotate(${min * 6} 100 100)`);
    $("#hHour").setAttribute("transform", `rotate(${hr * 30} 100 100)`);
    $("#clkCenter").textContent = `${h12}:${pad(d.getMinutes())}`;
    if (!document.getElementById("clockBody")) return; // window closed
    requestAnimationFrame(tick);
  }
  applyMode(); requestAnimationFrame(tick);
  REFRESH.clock = applyMode;
}

/* ================= NOTES ================= */
function renderNotes(stage) {
  stage.innerHTML = header("🗒️", "Notes") + `<div class="wbody notes-body">
    <div class="note-top"><input id="noteTitle" class="note-title no-drag" placeholder="Note title" /><button class="chip no-drag" id="newNoteBtn">+ New</button><button class="chip no-drag" id="allBtn">All</button></div>
    <div id="noteEditor" class="note-editor no-drag" contenteditable="true" spellcheck="true"></div>
    <div class="note-tb">
      <button class="fmt no-drag" data-cmd="bold"><b>B</b></button><button class="fmt no-drag" data-cmd="italic"><i>I</i></button>
      <button class="fmt no-drag" data-cmd="underline"><u>U</u></button><button class="fmt no-drag" data-cmd="strikeThrough"><s>S</s></button>
      <button class="fmt no-drag" data-cmd="insertUnorderedList">≣</button><button class="fmt no-drag" id="imgBtn" title="Insert image">🖼</button>
      <button class="fmt no-drag" id="emojiBtn" title="Insert emoji">😊</button>
    </div>
    <div class="emoji-pop no-drag" id="emojiPop" hidden></div>
    <div class="notes-list no-drag" id="notesList" hidden></div>
  </div>`;
  if (!data.notes.length) { data.notes.push({ id: uid(), title: "Welcome 👋", html: "A note that lives on your desktop.<br>Drag me by the title bar, resize from any edge.", updated: Date.now() }); data.activeNote = data.notes[0].id; }
  if (!data.activeNote) data.activeNote = data.notes[0].id;
  const cur = () => data.notes.find(n => n.id === data.activeNote) || data.notes[0];
  const load = () => { const n = cur(); data.activeNote = n.id; $("#noteTitle").value = n.title || ""; $("#noteEditor").innerHTML = n.html || ""; };
  const listEl = $("#notesList");
  const renderList = () => {
    listEl.innerHTML = "";
    data.notes.slice().sort((a, b) => (b.updated || 0) - (a.updated || 0)).forEach(n => {
      const it = document.createElement("div"); it.className = "nl-item" + (n.id === data.activeNote ? " active" : "");
      it.innerHTML = `<div class="nl-main"><div class="nl-title">${escapeHtml(n.title || "Untitled")}</div><div class="nl-date">${n.updated ? new Date(n.updated).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }) : ""}</div></div><div class="nl-del">✕</div>`;
      it.querySelector(".nl-del").onclick = (ev) => { ev.stopPropagation(); data.notes = data.notes.filter(x => x.id !== n.id); if (!data.notes.length) { data.notes.push({ id: uid(), title: "New Note", html: "", updated: Date.now() }); } if (data.activeNote === n.id) data.activeNote = data.notes[0].id; load(); renderList(); persist(); };
      it.onclick = () => { data.activeNote = n.id; load(); listEl.hidden = true; persist(); };
      listEl.appendChild(it);
    });
  };
  $("#newNoteBtn").onclick = () => { const n = { id: uid(), title: "New Note", html: "", updated: Date.now() }; data.notes.unshift(n); data.activeNote = n.id; load(); $("#noteTitle").focus(); persist(); };
  $("#allBtn").onclick = (e) => { e.stopPropagation(); if (listEl.hidden) { renderList(); listEl.hidden = false; } else listEl.hidden = true; };
  $("#noteTitle").oninput = () => { const n = cur(); n.title = $("#noteTitle").value; n.updated = Date.now(); persist(); };
  $("#noteEditor").oninput = () => { const n = cur(); n.html = $("#noteEditor").innerHTML; n.updated = Date.now(); persist(); };
  $$(".fmt", stage).forEach(b => { if (!b.dataset.cmd) return; b.onmousedown = (e) => e.preventDefault(); b.onclick = () => { $("#noteEditor").focus(); document.execCommand(b.dataset.cmd, false, null); cur().html = $("#noteEditor").innerHTML; persist(); }; });
  $("#imgBtn").onclick = async () => {
    try { const dataUrl = await window.api.pickImage(); if (dataUrl) { $("#noteEditor").focus(); document.execCommand("insertImage", false, dataUrl); cur().html = $("#noteEditor").innerHTML; persist(); } } catch (er) {}
  };
  // emoji picker
  const EMOJI = ["😀","😄","😍","😎","🥳","🤔","😢","😴","😇","🤩","👍","🙏","👀","💪","🤝","👏","🎉","❤️","🔥","✨","⭐","💯","✅","❌","💡","📌","⏰","📅","📝","🚀","☕","🌙","⚡","🍀","🎯","🌟","💚","😅"];
  const emojiPop = $("#emojiPop");
  emojiPop.innerHTML = EMOJI.map(e => `<button class="emoji no-drag">${e}</button>`).join("");
  emojiPop.addEventListener("mousedown", (e) => e.preventDefault());  // keep editor selection
  $("#emojiBtn").onmousedown = (e) => e.preventDefault();
  $("#emojiBtn").onclick = (e) => { e.stopPropagation(); emojiPop.hidden = !emojiPop.hidden; };
  emojiPop.addEventListener("click", (e) => { const b = e.target.closest(".emoji"); if (!b) return; $("#noteEditor").focus(); document.execCommand("insertText", false, b.textContent); cur().html = $("#noteEditor").innerHTML; persist(); emojiPop.hidden = true; });
  document.addEventListener("click", (e) => {
    if (!listEl.hidden && !e.target.closest("#notesList") && !e.target.closest("#allBtn")) listEl.hidden = true;
    if (!emojiPop.hidden && !e.target.closest("#emojiPop") && !e.target.closest("#emojiBtn")) emojiPop.hidden = true;
  });
  load();
  REFRESH.notes = () => { if (!data.notes.length) return; if (!data.notes.find(n => n.id === data.activeNote)) data.activeNote = data.notes[0].id; };
}

/* ================= REMINDERS ================= */
function renderReminders(stage) {
  stage.innerHTML = header("🔔", "Reminders") + `<div class="wbody">
    <input id="remText" class="field no-drag" placeholder="Type a reminder" />
    <div class="row2"><input type="date" id="remDate" class="field no-drag" /><input type="time" id="remTime" class="field no-drag" /></div>
    <button class="primary no-drag" id="remSet">Set</button>
    <div class="mini-list" id="remList"></div>
  </div>`;
  const list = $("#remList");
  const render = () => {
    list.innerHTML = "";
    [...data.reminders].sort((a, b) => a.at - b.at).forEach(r => {
      const w = new Date(r.at); const it = document.createElement("div"); it.className = "mini-item" + (r.fired ? " done" : "");
      it.innerHTML = `<div class="mi-main">${escapeHtml(r.text)}<small>${w.toLocaleDateString(undefined, { month: "short", day: "numeric" })} · ${w.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })}</small></div><div class="mi-x no-drag">✕</div>`;
      it.querySelector(".mi-x").onclick = () => { data.reminders = data.reminders.filter(y => y.id !== r.id); render(); persist(); };
      list.appendChild(it);
    });
  };
  const setR = () => { const text = $("#remText").value.trim(), d = $("#remDate").value, t = $("#remTime").value; if (!text || !d || !t) return; const at = new Date(`${d}T${t}`).getTime(); if (isNaN(at)) return; data.reminders.push({ id: uid(), text, at, fired: false }); $("#remText").value = ""; render(); persist(); };
  $("#remSet").onclick = setR; $("#remText").addEventListener("keydown", e => { if (e.key === "Enter") setR(); });
  const t = new Date(); $("#remDate").value = ymd(t.getFullYear(), t.getMonth(), t.getDate());
  render(); REFRESH.reminders = render;
}

/* ================= EVENTS ================= */
function renderEvents(stage) {
  stage.innerHTML = header("📅", "Events") + `<div class="wbody">
    <div class="cal-top"><button class="chip no-drag" id="calPrev">‹</button><span id="calLabel" class="cal-label">—</span><button class="chip no-drag" id="calNext">›</button></div>
    <div class="calendar no-drag" id="calendar"></div>
    <div class="event-add" id="eventAdd" hidden><input type="time" id="evTime" class="field small no-drag" /><input id="evTitle" class="field no-drag" placeholder="Event" /><button class="chip add no-drag" id="evAdd">Add</button></div>
    <div class="mini-list" id="evList"></div>
  </div>`;
  let calY = null, calM = null, sel = null;
  const draw = () => {
    const now = new Date(); if (calY == null) { calY = now.getFullYear(); calM = now.getMonth(); }
    $("#calLabel").textContent = new Date(calY, calM, 1).toLocaleDateString(undefined, { month: "short", year: "numeric" });
    const cal = $("#calendar"); cal.innerHTML = "";
    ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"].forEach(d => { const e = document.createElement("div"); e.className = "cal-dow"; e.textContent = d; cal.appendChild(e); });
    const first = new Date(calY, calM, 1).getDay(), days = new Date(calY, calM + 1, 0).getDate();
    for (let i = 0; i < first; i++) { const e = document.createElement("div"); e.className = "cal-day out"; cal.appendChild(e); }
    for (let dd = 1; dd <= days; dd++) { const cell = document.createElement("div"); cell.className = "cal-day"; cell.textContent = dd; const key = ymd(calY, calM, dd); if (calY === now.getFullYear() && calM === now.getMonth() && dd === now.getDate()) cell.classList.add("today"); if (sel === key) cell.classList.add("sel"); if (data.events.some(ev => ev.date === key)) cell.classList.add("has"); cell.onclick = () => { sel = key; draw(); drawList(); $("#eventAdd").hidden = false; }; cal.appendChild(cell); }
  };
  const drawList = () => { const box = $("#evList"); box.innerHTML = ""; if (!sel) return; data.events.filter(e => e.date === sel).sort((a, b) => (a.time || "").localeCompare(b.time || "")).forEach(ev => { const it = document.createElement("div"); it.className = "mini-item"; it.innerHTML = `<div class="mi-main">${escapeHtml(ev.title)}<small>${ev.time ? formatTime(ev.time) : "all day"}</small></div><div class="mi-x no-drag">✕</div>`; it.querySelector(".mi-x").onclick = () => { data.events = data.events.filter(y => y.id !== ev.id); drawList(); draw(); persist(); }; box.appendChild(it); }); };
  $("#calPrev").onclick = () => { calM--; if (calM < 0) { calM = 11; calY--; } draw(); };
  $("#calNext").onclick = () => { calM++; if (calM > 11) { calM = 0; calY++; } draw(); };
  const add = () => { const title = $("#evTitle").value.trim(); if (!title || !sel) return; data.events.push({ id: uid(), date: sel, time: $("#evTime").value, title }); $("#evTitle").value = ""; $("#evTime").value = ""; drawList(); draw(); persist(); };
  $("#evAdd").onclick = add; $("#evTitle").addEventListener("keydown", e => { if (e.key === "Enter") add(); });
  draw(); REFRESH.events = () => { draw(); drawList(); };
}

/* ================= TIMER + STOPWATCH ================= */
function renderTimer(stage) {
  stage.innerHTML = header("⏱️", "Timer") + `<div class="wbody">
    <div class="seg"><button class="seg-btn active no-drag" id="tabTimer">Timer</button><button class="seg-btn no-drag" id="tabStop">Stopwatch</button></div>
    <div id="timerView">
      <div class="timer-wrap"><svg class="ring" viewBox="0 0 120 120"><circle class="ring-bg" cx="60" cy="60" r="52"/><circle class="ring-fg" id="ringFg" cx="60" cy="60" r="52"/></svg><div class="timer-display" id="timerDisplay">25:00</div></div>
      <div class="presets"><button class="chip no-drag" data-min="5">5m</button><button class="chip no-drag" data-min="10">10m</button><button class="chip no-drag" data-min="15">15m</button><button class="chip no-drag" data-min="25">25m</button><button class="chip no-drag" id="tMinus">−1</button><button class="chip no-drag" id="tPlus">+1</button></div>
      <div class="custom-time" title="Set exact time — days : hours : minutes : seconds">
        <input type="number" id="ctD" class="ct-in no-drag" min="0" max="999" placeholder="dd" /><span class="ct-sep">:</span>
        <input type="number" id="ctH" class="ct-in no-drag" min="0" max="23" placeholder="hh" /><span class="ct-sep">:</span>
        <input type="number" id="ctM" class="ct-in no-drag" min="0" max="59" placeholder="mm" /><span class="ct-sep">:</span>
        <input type="number" id="ctS" class="ct-in no-drag" min="0" max="59" placeholder="ss" />
        <button class="chip add no-drag" id="ctSet">Set</button>
      </div>
      <div class="row2"><button class="primary no-drag" id="timerToggle">Start</button><button class="ghost no-drag" id="timerReset">Reset</button></div>
    </div>
    <div id="stopView" hidden><div class="timer-wrap"><div class="timer-display big" id="stopDisplay">00:00.0</div></div><div class="row2"><button class="primary no-drag" id="stopToggle">Start</button><button class="ghost no-drag" id="stopReset">Reset</button></div><button class="chip wide no-drag" id="lapBtn">Lap</button><div class="mini-list" id="lapList"></div></div>
  </div>`;
  const RING = 2 * Math.PI * 52; let rem = data.timer.dur || 1500, tot = rem, run = false, iv = null;
  const fmt = s => {
    s = Math.max(0, Math.floor(s));
    const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    if (d > 0) return `${d}:${pad(h)}:${pad(m)}:${pad(sec)}`;
    if (h > 0) return `${h}:${pad(m)}:${pad(sec)}`;
    return `${pad(m)}:${pad(sec)}`;
  };
  const draw = () => { $("#timerDisplay").textContent = fmt(Math.max(0, rem)); $("#ringFg").style.strokeDashoffset = String(RING * (1 - (tot ? rem / tot : 0))); };
  const stop = () => { run = false; $("#timerToggle").textContent = rem <= 0 ? "Start" : "Resume"; clearInterval(iv); };
  const start = () => { if (rem <= 0) return; run = true; $("#timerToggle").textContent = "Pause"; clearInterval(iv); iv = setInterval(() => { rem--; if (rem <= 0) { rem = 0; draw(); stop(); alertUser("Timer finished ⏱️", "Countdown reached zero."); return; } draw(); }, 1000); };
  const set = s => { tot = rem = s; data.timer.dur = s; stop(); $("#timerToggle").textContent = "Start"; draw(); persist(); };
  $("#timerToggle").onclick = () => run ? stop() : start();
  $("#timerReset").onclick = () => { stop(); rem = tot; $("#timerToggle").textContent = "Start"; draw(); };
  $$(".presets .chip[data-min]", stage).forEach(b => b.onclick = () => set(parseInt(b.dataset.min) * 60));
  $("#tPlus").onclick = () => set(tot + 60); $("#tMinus").onclick = () => set(Math.max(1, tot - 60));
  const ctSet = () => {
    const d = parseInt($("#ctD").value) || 0, h = parseInt($("#ctH").value) || 0, m = parseInt($("#ctM").value) || 0, sc = parseInt($("#ctS").value) || 0;
    const total = d * 86400 + h * 3600 + m * 60 + sc;
    if (total > 0) set(total);
  };
  $("#ctSet").onclick = ctSet;
  ["ctD", "ctH", "ctM", "ctS"].forEach(id => $("#" + id).addEventListener("keydown", e => { if (e.key === "Enter") ctSet(); }));
  draw();
  // stopwatch
  let el = 0, srun = false, sst = 0, laps = [], sraf = null;
  const sfmt = ms => { const t = Math.floor(ms / 100); return `${pad(Math.floor(t / 600))}:${pad(Math.floor(t / 10) % 60)}.${t % 10}`; };
  const sdraw = () => $("#stopDisplay").textContent = sfmt(el);
  const sLoop = () => { if (!srun) return; el = Date.now() - sst; sdraw(); sraf = requestAnimationFrame(sLoop); };
  $("#stopToggle").onclick = () => { if (srun) { srun = false; cancelAnimationFrame(sraf); $("#stopToggle").textContent = "Resume"; } else { srun = true; sst = Date.now() - el; sraf = requestAnimationFrame(sLoop); $("#stopToggle").textContent = "Stop"; } };
  $("#stopReset").onclick = () => { srun = false; cancelAnimationFrame(sraf); el = 0; laps = []; sdraw(); $("#lapList").innerHTML = ""; $("#stopToggle").textContent = "Start"; };
  $("#lapBtn").onclick = () => { if (!el) return; laps.unshift(sfmt(el)); $("#lapList").innerHTML = laps.map((l, i) => `<div class="mini-item"><div class="mi-main">Lap ${laps.length - i} <small>${l}</small></div></div>`).join(""); };
  sdraw();
  const tab = (t) => { $("#tabTimer").classList.toggle("active", t); $("#tabStop").classList.toggle("active", !t); $("#timerView").hidden = !t; $("#stopView").hidden = t; };
  $("#tabTimer").onclick = () => tab(true); $("#tabStop").onclick = () => tab(false);
}

/* ================= TO-DO ================= */
function renderTodo(stage) {
  stage.innerHTML = header("✔️", "To-do") + `<div class="wbody"><div class="row"><input id="todoText" class="field no-drag" placeholder="Add a task" /><button class="chip add no-drag" id="todoAdd">Add</button></div><div class="mini-list" id="todoList"></div></div>`;
  const list = $("#todoList");
  const render = () => { list.innerHTML = ""; data.todos.forEach(t => { const it = document.createElement("div"); it.className = "mini-item" + (t.done ? " done" : ""); it.innerHTML = `<div class="check no-drag">✓</div><div class="mi-main">${escapeHtml(t.text)}</div><div class="mi-x no-drag">✕</div>`; it.querySelector(".check").onclick = () => { t.done = !t.done; render(); persist(); }; it.querySelector(".mi-x").onclick = () => { data.todos = data.todos.filter(y => y.id !== t.id); render(); persist(); }; list.appendChild(it); }); };
  const add = () => { const v = $("#todoText").value.trim(); if (!v) return; data.todos.push({ id: uid(), text: v, done: false }); $("#todoText").value = ""; render(); persist(); };
  $("#todoAdd").onclick = add; $("#todoText").addEventListener("keydown", e => { if (e.key === "Enter") add(); });
  render(); REFRESH.todo = render;
}

/* ================= CALCULATOR ================= */
function renderCalc(stage) {
  stage.innerHTML = header("🧮", "Calculator") + `<div class="wbody"><div class="calc-display" id="calcDisplay">0</div><div class="calc-grid no-drag" id="calcGrid">
    <button class="calc op" data-k="C">C</button><button class="calc op" data-k="±">±</button><button class="calc op" data-k="%">%</button><button class="calc op" data-k="/">÷</button>
    <button class="calc" data-k="7">7</button><button class="calc" data-k="8">8</button><button class="calc" data-k="9">9</button><button class="calc op" data-k="*">×</button>
    <button class="calc" data-k="4">4</button><button class="calc" data-k="5">5</button><button class="calc" data-k="6">6</button><button class="calc op" data-k="-">−</button>
    <button class="calc" data-k="1">1</button><button class="calc" data-k="2">2</button><button class="calc" data-k="3">3</button><button class="calc op" data-k="+">+</button>
    <button class="calc" data-k="0">0</button><button class="calc" data-k=".">.</button><button class="calc eq" data-k="=">=</button></div></div>`;
  let s = "0"; const disp = $("#calcDisplay"); const upd = () => disp.textContent = (s.length > 14 && s !== "Error") ? String(Number(s)) : s;
  // safe manual evaluator (no eval — CSP blocks Function())
  const ev = (str) => {
    str = String(str).replace(/\s+/g, "").replace(/[+\-*/.]+$/, "");
    if (str === "") return 0;
    if (!/^[-+*/.\d]+$/.test(str)) return null;
    const nums = [], ops = []; let i = 0, wantNum = true;
    while (i < str.length) {
      let c = str[i];
      if (!wantNum && "+-*/".includes(c)) {
        ops.push(c); i++; wantNum = true; continue;
      }
      if (wantNum) {
        let sign = 1;
        while (i < str.length && (str[i] === "+" || str[i] === "-")) {
          if (str[i] === "-") sign *= -1;
          i++;
        }
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
  const apply = (k) => {
    if (k === "C") s = "0";
    else if (k === "=") { const r = ev(s); s = (r === null) ? "Error" : String(r); }
    else if (k === "±") s = s.startsWith("-") ? s.slice(1) : (s !== "0" && s !== "Error" ? "-" + s : s);
    else if (k === "%") { const r = ev(s); s = (r === null) ? "Error" : String(r / 100); }
    else if (k === "back") { if (s === "Error") s = "0"; else { s = s.slice(0, -1); if (s === "" || s === "-") s = "0"; } }
    else if ("+-*/".includes(k)) { if (s === "Error") s = "0"; if (/[+\-*/]$/.test(s)) s = s.slice(0, -1) + k; else s += k; }
    else { if (s === "0" && k !== ".") s = k; else if (s === "Error") s = (k === ".") ? "0." : k; else s += k; }
    upd();
  };
  $("#calcGrid").addEventListener("click", (e) => { const b = e.target.closest(".calc"); if (!b) return; apply(b.dataset.k); });
  document.addEventListener("keydown", (e) => {
    if (e.target.closest("input,textarea,[contenteditable]")) return;
    const key = e.key;
    if (/^[0-9]$/.test(key)) apply(key);
    else if (key === ".") apply(".");
    else if (key === "+" || key === "-" || key === "*" || key === "/") apply(key);
    else if (key === "Enter" || key === "=") { e.preventDefault(); apply("="); }
    else if (key === "Backspace") { e.preventDefault(); apply("back"); }
    else if (key === "Escape" || key.toLowerCase() === "c") apply("C");
    else if (key === "%") apply("%");
    else return;
  });
}

/* ================= SYSMON ================= */
function renderSysMon(stage) {
  stage.innerHTML = header("🖥️", "SysMon") + `<div class="wbody">
    <div class="sys-ring-wrap">
      <div class="sys-lbl">CPU</div>
      <svg class="sys-ring" viewBox="0 0 100 100"><circle cx="50" cy="50" r="40" class="ring-bg"/><circle cx="50" cy="50" r="40" class="ring-fg" id="cpuRing"/></svg>
      <div class="sys-val" id="cpuVal">--%</div>
    </div>
    <div class="sys-ring-wrap">
      <div class="sys-lbl">RAM</div>
      <svg class="sys-ring" viewBox="0 0 100 100"><circle cx="50" cy="50" r="40" class="ring-bg"/><circle cx="50" cy="50" r="40" class="ring-fg" id="ramRing"/></svg>
      <div class="sys-val" id="ramVal">--%</div>
    </div>
    <div class="sys-info" id="sysUptime">Uptime: --</div>
  </div>`;

  let lastCpus = null;
  const CIRC = 2 * Math.PI * 40;
  $("#cpuRing").style.strokeDasharray = CIRC;
  $("#ramRing").style.strokeDasharray = CIRC;
  $("#cpuRing").style.strokeDashoffset = CIRC;
  $("#ramRing").style.strokeDashoffset = CIRC;

  const update = async () => {
    try {
      const stats = await window.api.getSysStats();
      if (!stats) return;

      // RAM
      const ramUsed = stats.totalmem - stats.freemem;
      const ramPct = ramUsed / stats.totalmem;
      $("#ramVal").textContent = Math.round(ramPct * 100) + "%";
      $("#ramRing").style.strokeDashoffset = CIRC * (1 - ramPct);

      // CPU
      if (lastCpus) {
        let idleDiff = 0, totalDiff = 0;
        stats.cpus.forEach((c, i) => {
          const lc = lastCpus[i];
          const total = Object.values(c).reduce((a, b) => a + b) - Object.values(lc).reduce((a, b) => a + b);
          totalDiff += total;
          idleDiff += c.idle - lc.idle;
        });
        const cpuPct = totalDiff > 0 ? 1 - (idleDiff / totalDiff) : 0;
        $("#cpuVal").textContent = Math.round(cpuPct * 100) + "%";
        $("#cpuRing").style.strokeDashoffset = CIRC * (1 - cpuPct);
      }
      lastCpus = stats.cpus;

      // Uptime
      const up = stats.uptime;
      const h = Math.floor(up / 3600);
      const m = Math.floor((up % 3600) / 60);
      $("#sysUptime").textContent = `Uptime: ${h}h ${m}m`;

    } catch (e) {}
  };
  update();
  const iv = setInterval(update, 1000);
  REFRESH.sysmon = () => {};
}

const RENDER = { hub: renderHub, clock: renderClock, notes: renderNotes, reminders: renderReminders, events: renderEvents, timer: renderTimer, todo: renderTodo, calc: renderCalc, sysmon: renderSysMon };

/* ================= BOOT ================= */
async function boot() {
  document.title = (WT[TYPE] || "Glass") + " — Glass Widget";
  try { const cfg = await window.api.cfg(); if (cfg) { opacityVal = cfg.opacity; if (typeof cfg.winOpacity === "number") winOpacityVal = cfg.winOpacity; } } catch (e) {}
  try { data = (await window.api.load()) || {}; } catch (e) { data = {}; }
  ensureData();
  applyOpacity();
  (RENDER[TYPE] || renderHub)($("#stage"));
  wireCommon();
  initResizeHandles();
  scaleStage(); window.addEventListener("resize", scaleStage);
  syncPin();
  window.api.onOpacity(v => { opacityVal = v; applyOpacity(); const op = $("#opac"); if (op) op.value = Math.round(v * 100); });
  window.api.onPin(() => syncPin());
  window.api.onChime(() => chime());
  window.api.onDataChanged(async () => { try { data = (await window.api.load()) || {}; ensureData(); if (REFRESH[TYPE]) REFRESH[TYPE](); } catch (e) {} });
}
document.addEventListener("DOMContentLoaded", boot);

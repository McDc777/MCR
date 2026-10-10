"use strict";
const $ = (s, e = document) => e.querySelector(s);
const $$ = (s, e = document) => Array.from(e.querySelectorAll(s));
const uid = () => Math.random().toString(36).slice(2, 9);
const pad = (n) => String(n).padStart(2, "0");
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

const PANELS = [
  { key: "notes", title: "Notes", side: "left" },
  { key: "reminders", title: "Reminders", side: "right" },
  { key: "events", title: "Events", side: "right" },
  { key: "timer", title: "Timer", side: "right" },
  { key: "todo", title: "To-do", side: "right" },
  { key: "calc", title: "Calculator", side: "right" }
];
const ALL_KEYS = PANELS.map(p => p.key);

let data = {
  notes: [], activeNote: null, reminders: [], events: [], todos: [],
  timer: { dur: 1500 },
  settings: {
    opacity: 0.55, clock: true,
    panels: { notes: true, reminders: true, events: true, timer: true, todo: true, calc: true },
    min: {}, max: null, weights: {}, split: 0.56,
    layout: { left: ["notes"], right: ["reminders", "events", "timer", "todo", "calc"] }
  }
};
let saveT;
function save() { clearTimeout(saveT); saveT = setTimeout(() => { try { window.api.save(JSON.parse(JSON.stringify(data))); } catch (e) {} }, 250); }
const S = () => data.settings;
const panelEl = (k) => document.getElementById("panel-" + k);

/* ============ CHIME ============ */
function chime() {
  try {
    const AC = window.AudioContext || window.webkitAudioContext;
    const ac = new AC();
    const master = ac.createGain(); master.gain.value = 0.95; master.connect(ac.destination);
    const C = 523.25, E = 659.25, G = 783.99, C2 = 1046.5, E2 = 1318.51, G2 = 1567.98;
    const melody = [C, E, G, C2, E2, C2, G, C2, E2, G2];
    const step = 0.15;
    const note = (freq, t, dur) => {
      [[freq, "triangle", 0.28], [freq * 2, "sine", 0.12], [freq * 1.5, "sine", 0.07]].forEach(([f, type, amp]) => {
        const o = ac.createOscillator(), g = ac.createGain();
        o.type = type; o.frequency.value = f; o.connect(g); g.connect(master);
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(amp, t + 0.012);
        g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
        o.start(t); o.stop(t + dur + 0.05);
      });
    };
    let t = ac.currentTime + 0.03;
    melody.forEach((f, i) => note(f, t + i * step, i >= melody.length - 1 ? 1.4 : 0.5));
    const end = t + melody.length * step + 0.05;
    [C2, E2, G2].forEach(f => note(f, end, 1.6));
    setTimeout(() => { try { ac.close(); } catch (e) {} }, 4200);
  } catch (e) {}
}
function alertUser(title, body) { try { window.api.notify(title, body); } catch (e) {} chime(); }

/* ============ AUTO-FIT (scale each panel's content to its box) ============ */
let fitObs = null, fitQueue = new Set(), fitRaf = null;
function fitPanel(panel) {
  if (!panel || panel.classList.contains("is-hidden")) return;
  const body = panel.querySelector(".panel-body");
  const fit = body && body.querySelector(".fit");
  if (!fit) return;
  fit.style.zoom = "1";
  const bw = body.clientWidth, bh = body.clientHeight;
  const cw = fit.scrollWidth, ch = fit.scrollHeight;
  if (!bw || !bh || !cw || !ch) return;
  let z = Math.min(bw / cw, bh / ch, 1) * 0.99;
  z = Math.max(0.5, z);
  fit.style.zoom = String(z);
  body.style.overflowY = (ch * z > bh + 2) ? "auto" : "hidden";
}
function scheduleFit(panel) {
  fitQueue.add(panel);
  if (fitRaf) return;
  fitRaf = requestAnimationFrame(() => { fitRaf = null; const q = [...fitQueue]; fitQueue.clear(); q.forEach(fitPanel); });
}
function refit(key) { const el = panelEl(key); if (el) scheduleFit(el); }
function fitAll() { PANELS.forEach(p => { if (p.key !== "notes") scheduleFit(panelEl(p.key)); }); }
function setupFit() {
  fitObs = new ResizeObserver(entries => { for (const e of entries) { const p = e.target.closest(".panel"); if (p) scheduleFit(p); } });
  PANELS.forEach(p => { if (p.key === "notes") return; const b = panelEl(p.key).querySelector(".panel-body"); if (b) fitObs.observe(b); });
}

/* ============ LAYOUT ============ */
function arrangeFromLayout() {
  const s = S(), colL = $("#colLeft"), colR = $("#colRight");
  const known = new Set([...(s.layout.left || []), ...(s.layout.right || [])]);
  (s.layout.left || []).forEach(k => { const el = panelEl(k); if (el) colL.appendChild(el); });
  (s.layout.right || []).forEach(k => { const el = panelEl(k); if (el) colR.appendChild(el); });
  PANELS.forEach(p => { if (!known.has(p.key)) (p.side === "left" ? colL : colR).appendChild(panelEl(p.key)); });
}
function persistLayout() {
  const rd = (col) => $$(".panel", col).map(p => p.dataset.panel);
  S().layout = { left: rd($("#colLeft")), right: rd($("#colRight")) };
}
function applyLayout() {
  const s = S();
  document.documentElement.style.setProperty("--shell-a", s.opacity);
  $("#brandClock").classList.toggle("is-hidden", !s.clock);
  const maxKey = (s.max && s.panels[s.max] !== false && !s.min[s.max]) ? s.max : null;

  PANELS.forEach(p => {
    const el = panelEl(p.key); if (!el) return;
    const active = s.panels[p.key] !== false;
    const minimised = active && !!s.min[p.key];
    let show = active && !minimised;
    if (maxKey) show = (p.key === maxKey);
    el.classList.toggle("is-hidden", !show);
    el.classList.toggle("maximized", maxKey === p.key);
    el.style.flexGrow = String(s.weights[p.key] || 1);
  });

  const colL = $("#colLeft"), colR = $("#colRight"), divider = $("#divider");
  const colHasVisible = (col) => $$(".panel", col).some(x => !x.classList.contains("is-hidden"));
  let showL = colHasVisible(colL), showR = colHasVisible(colR);
  if (maxKey) { const inR = $$(".panel", colR).some(x => x.dataset.panel === maxKey); showL = !inR; showR = inR; }
  colL.classList.toggle("is-hidden", !showL);
  colR.classList.toggle("is-hidden", !showR);
  divider.classList.toggle("is-hidden", !(showL && showR && !maxKey));

  if (showL && showR && !maxKey) {
    colL.style.flex = "0 0 auto"; colL.style.width = (clamp(s.split || 0.56, 0.2, 0.8) * 100).toFixed(1) + "%"; colR.style.flex = "1 1 auto";
  } else { colL.style.flex = "1 1 auto"; colL.style.width = "auto"; colR.style.flex = "1 1 auto"; }

  renderChips();
  fitAll();
}
function renderChips() {
  const box = $("#minChips"); box.innerHTML = "";
  PANELS.forEach(p => {
    if (S().panels[p.key] !== false && S().min[p.key]) {
      const c = document.createElement("button");
      c.className = "min-chip"; c.innerHTML = `▢ ${p.title}`; c.title = "Restore " + p.title;
      c.onclick = () => { delete S().min[p.key]; applyLayout(); save(); };
      box.appendChild(c);
    }
  });
}
function initPanelControls() {
  document.addEventListener("click", (e) => {
    const btn = e.target.closest(".pc[data-act]"); if (!btn) return;
    const panel = btn.closest(".panel"); if (!panel) return;
    const key = panel.dataset.panel, act = btn.dataset.act, s = S();
    if (act === "min") { if (s.max === key) s.max = null; s.min[key] = true; }
    else if (act === "max") { delete s.min[key]; s.max = (s.max === key) ? null : key; }
    else if (act === "close") { if (s.max === key) s.max = null; s.panels[key] = false; syncSettingsChecks(); }
    applyLayout(); save();
  });
}

/* ============ REORDER (drag panels by header) ============ */
function initReorder() {
  document.addEventListener("pointerdown", (e) => {
    const head = e.target.closest(".panel-head"); if (!head) return;
    if (e.target.closest(".pc, .seg, button, input, select, textarea")) return;
    const panel = head.closest(".panel"); if (!panel) return;
    const startX = e.clientX, startY = e.clientY; let dragging = false;
    const move = (ev) => {
      if (!dragging) {
        if (Math.abs(ev.clientX - startX) + Math.abs(ev.clientY - startY) < 6) return;
        dragging = true; panel.classList.add("dragging"); document.body.classList.add("reordering");
      }
      const colL = $("#colLeft"), colR = $("#colRight");
      const rRect = colR.classList.contains("is-hidden") ? null : colR.getBoundingClientRect();
      const targetCol = (rRect && ev.clientX >= rRect.left) ? colR : colL;
      const sibs = $$(".panel", targetCol).filter(x => x !== panel && !x.classList.contains("is-hidden"));
      let before = null;
      for (const s of sibs) { const r = s.getBoundingClientRect(); if (ev.clientY < r.top + r.height / 2) { before = s; break; } }
      if (before) targetCol.insertBefore(panel, before); else targetCol.appendChild(panel);
    };
    const up = () => {
      document.removeEventListener("pointermove", move); document.removeEventListener("pointerup", up);
      if (dragging) { panel.classList.remove("dragging"); document.body.classList.remove("reordering"); persistLayout(); applyLayout(); save(); }
    };
    document.addEventListener("pointermove", move); document.addEventListener("pointerup", up);
  });
}

/* ============ PANEL RESIZE (weight via bottom handle) ============ */
function initWeights() {
  document.addEventListener("pointerdown", (e) => {
    const h = e.target.closest(".panel-resize"); if (!h) return;
    const panel = h.closest(".panel"); const key = panel.dataset.panel;
    const startY = e.clientY, startW = S().weights[key] || 1;
    try { h.setPointerCapture(e.pointerId); } catch (er) {}
    const move = (ev) => { const w = clamp(startW + (ev.clientY - startY) * 0.012, 0.3, 8); S().weights[key] = w; panel.style.flexGrow = String(w); };
    const up = () => { document.removeEventListener("pointermove", move); document.removeEventListener("pointerup", up); save(); };
    document.addEventListener("pointermove", move); document.addEventListener("pointerup", up);
    e.preventDefault();
  });
}

/* ============ DIVIDER (column width) ============ */
function initDivider() {
  const div = $("#divider"), body = $("#body"); let dragging = false;
  div.addEventListener("pointerdown", (e) => { dragging = true; div.setPointerCapture(e.pointerId); e.preventDefault(); });
  div.addEventListener("pointermove", (e) => {
    if (!dragging) return;
    const r = body.getBoundingClientRect();
    const ratio = clamp((e.clientX - r.left - 10) / (r.width - 20), 0.2, 0.8);
    S().split = ratio; $("#colLeft").style.width = (ratio * 100).toFixed(1) + "%";
  });
  const end = (e) => { if (dragging) { dragging = false; try { div.releasePointerCapture(e.pointerId); } catch (er) {} save(); } };
  div.addEventListener("pointerup", end); div.addEventListener("pointercancel", end);
}

/* ============ SETTINGS ============ */
function buildSettings() {
  const box = $("#setPanels"); box.innerHTML = "";
  PANELS.forEach(p => {
    const lab = document.createElement("label");
    const cb = document.createElement("input"); cb.type = "checkbox"; cb.dataset.pkey = p.key; cb.checked = S().panels[p.key] !== false;
    cb.onchange = () => { S().panels[p.key] = cb.checked; if (!cb.checked && S().max === p.key) S().max = null; applyLayout(); save(); };
    const sp = document.createElement("span"); sp.textContent = p.title;
    lab.appendChild(cb); lab.appendChild(sp); box.appendChild(lab);
  });
  const op = $("#opacityRange");
  op.value = Math.round(S().opacity * 100); $("#opacityVal").textContent = op.value + "%";
  op.oninput = () => { S().opacity = op.value / 100; $("#opacityVal").textContent = op.value + "%"; document.documentElement.style.setProperty("--shell-a", S().opacity); save(); };
  $("#tglClock").checked = S().clock !== false;
  $("#tglClock").onchange = (e) => { S().clock = e.target.checked; applyLayout(); save(); };
}
function syncSettingsChecks() { $$("#setPanels input[data-pkey]").forEach(cb => { cb.checked = S().panels[cb.dataset.pkey] !== false; }); }
function initSettings() {
  buildSettings();
  $("#settingsBtn").onclick = async (e) => {
    e.stopPropagation(); const o = $("#settingsOverlay");
    if (o.hidden) { syncSettingsChecks(); try { $("#autoStartChk").checked = await window.api.getAutoStart(); } catch (er) {} o.hidden = false; } else o.hidden = true;
  };
  $("#setClose").onclick = () => { $("#settingsOverlay").hidden = true; };
  $("#settingsOverlay").addEventListener("click", (e) => { if (e.target === $("#settingsOverlay")) $("#settingsOverlay").hidden = true; });
  $("#autoStartChk").onchange = (e) => window.api.setAutoStart(e.target.checked);
  $("#quitBtn").onclick = () => window.api.quit();
}

/* ============ CLOCK ============ */
function renderClock() {
  const d = new Date(); let h = d.getHours(); const ampm = h >= 12 ? "PM" : "AM"; h = h % 12 || 12;
  $("#clockTime").textContent = `${h}:${pad(d.getMinutes())} ${ampm}`;
  $("#clockDate").textContent = d.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
}

/* ============ NOTES ============ */
function activeNote() { return data.notes.find(n => n.id === data.activeNote) || data.notes[0]; }
function loadNoteIntoEditor() { const n = activeNote(); if (!n) return; data.activeNote = n.id; $("#noteTitle").value = n.title || ""; $("#noteEditor").innerHTML = n.html || ""; }
function newNote() { const n = { id: uid(), title: "New Note", html: "", updated: Date.now() }; data.notes.unshift(n); data.activeNote = n.id; loadNoteIntoEditor(); $("#noteTitle").focus(); save(); }
function renderNotesList() {
  const box = $("#notesList"); box.innerHTML = "";
  data.notes.slice().sort((a, b) => (b.updated || 0) - (a.updated || 0)).forEach(n => {
    const item = document.createElement("div"); item.className = "nl-item" + (n.id === data.activeNote ? " active" : "");
    const main = document.createElement("div"); main.className = "nl-main";
    const t = document.createElement("div"); t.className = "nl-title"; t.textContent = n.title || "Untitled";
    const dt = document.createElement("div"); dt.className = "nl-date"; dt.textContent = n.updated ? new Date(n.updated).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }) : "";
    main.appendChild(t); main.appendChild(dt);
    const del = document.createElement("div"); del.className = "nl-del"; del.textContent = "✕";
    del.onclick = (ev) => { ev.stopPropagation(); data.notes = data.notes.filter(x => x.id !== n.id); if (!data.notes.length) newNote(); if (data.activeNote === n.id) data.activeNote = data.notes[0].id; loadNoteIntoEditor(); renderNotesList(); save(); };
    item.appendChild(main); item.appendChild(del);
    item.onclick = () => { data.activeNote = n.id; loadNoteIntoEditor(); $("#notesList").hidden = true; save(); };
    box.appendChild(item);
  });
}
function initNotes() {
  $("#addNoteBtn").onclick = newNote;
  $("#newNoteBtn").onclick = newNote;
  $("#noteTitle").oninput = () => { const n = activeNote(); if (n) { n.title = $("#noteTitle").value; n.updated = Date.now(); } save(); };
  $("#noteEditor").oninput = () => { const n = activeNote(); if (n) { n.html = $("#noteEditor").innerHTML; n.updated = Date.now(); } save(); };
  $$(".fmt").forEach(b => { if (!b.dataset.cmd) return; b.onmousedown = (e) => e.preventDefault(); b.onclick = () => { $("#noteEditor").focus(); document.execCommand(b.dataset.cmd, false, null); const n = activeNote(); if (n) n.html = $("#noteEditor").innerHTML; save(); }; });
  $("#imgBtn").onclick = () => $("#imgInput").click();
  $("#imgInput").onchange = (e) => { const f = e.target.files[0]; if (!f) return; const r = new FileReader(); r.onload = () => { $("#noteEditor").focus(); document.execCommand("insertImage", false, r.result); const n = activeNote(); if (n) n.html = $("#noteEditor").innerHTML; save(); }; r.readAsDataURL(f); e.target.value = ""; };
  $("#showAllBtn").onclick = (e) => { e.stopPropagation(); const l = $("#notesList"); if (l.hidden) { renderNotesList(); l.hidden = false; } else l.hidden = true; };
  document.addEventListener("click", (e) => {
    if (!$("#notesList").hidden && !e.target.closest("#notesList") && !e.target.closest("#showAllBtn")) $("#notesList").hidden = true;
    if (!$("#settingsOverlay").hidden && !e.target.closest(".settings-card") && !e.target.closest("#settingsBtn")) $("#settingsOverlay").hidden = true;
  });
}

/* ============ REMINDERS ============ */
function escapeHtml(s) { return (s || "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function renderReminders() {
  const box = $("#remList"); box.innerHTML = "";
  [...data.reminders].sort((a, b) => a.at - b.at).forEach(r => {
    const it = document.createElement("div"); it.className = "mini-item" + (r.fired ? " done" : "");
    const main = document.createElement("div"); main.className = "mi-main"; const w = new Date(r.at);
    main.innerHTML = `${escapeHtml(r.text)}<small>${w.toLocaleDateString(undefined, { month: "short", day: "numeric" })} · ${w.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })}</small>`;
    const x = document.createElement("div"); x.className = "mi-x"; x.textContent = "✕";
    x.onclick = () => { data.reminders = data.reminders.filter(y => y.id !== r.id); renderReminders(); save(); };
    it.appendChild(main); it.appendChild(x); box.appendChild(it);
  });
  refit("reminders");
}
function initReminders() {
  const setR = () => {
    const text = $("#remText").value.trim(), d = $("#remDate").value, t = $("#remTime").value;
    if (!text || !d || !t) return; const at = new Date(`${d}T${t}`).getTime(); if (isNaN(at)) return;
    data.reminders.push({ id: uid(), text, at, fired: false }); $("#remText").value = ""; renderReminders(); save();
  };
  $("#remSet").onclick = setR; $("#remText").addEventListener("keydown", e => { if (e.key === "Enter") setR(); });
}

/* ============ EVENTS ============ */
let calY, calM, selDate = null;
function ymd(y, m, d) { return `${y}-${pad(m + 1)}-${pad(d)}`; }
function renderCalendar() {
  const now = new Date(); if (calY == null) { calY = now.getFullYear(); calM = now.getMonth(); }
  $("#calLabel").textContent = new Date(calY, calM, 1).toLocaleDateString(undefined, { month: "short", year: "numeric" });
  const cal = $("#calendar"); cal.innerHTML = "";
  ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"].forEach(d => { const e = document.createElement("div"); e.className = "cal-dow"; e.textContent = d; cal.appendChild(e); });
  const first = new Date(calY, calM, 1).getDay(), days = new Date(calY, calM + 1, 0).getDate();
  for (let i = 0; i < first; i++) { const e = document.createElement("div"); e.className = "cal-day out"; cal.appendChild(e); }
  for (let d = 1; d <= days; d++) {
    const cell = document.createElement("div"); cell.className = "cal-day"; cell.textContent = d; const key = ymd(calY, calM, d);
    if (calY === now.getFullYear() && calM === now.getMonth() && d === now.getDate()) cell.classList.add("today");
    if (selDate === key) cell.classList.add("sel");
    if (data.events.some(ev => ev.date === key)) cell.classList.add("has");
    cell.onclick = () => { selDate = key; renderCalendar(); renderEventList(); $("#eventAdd").hidden = false; };
    cal.appendChild(cell);
  }
  refit("events");
}
function renderEventList() {
  const box = $("#evList"); box.innerHTML = ""; if (!selDate) return;
  data.events.filter(e => e.date === selDate).sort((a, b) => (a.time || "").localeCompare(b.time || "")).forEach(ev => {
    const it = document.createElement("div"); it.className = "mini-item";
    const main = document.createElement("div"); main.className = "mi-main"; main.innerHTML = `${escapeHtml(ev.title)}<small>${ev.time ? formatTime(ev.time) : "all day"}</small>`;
    const x = document.createElement("div"); x.className = "mi-x"; x.textContent = "✕";
    x.onclick = () => { data.events = data.events.filter(y => y.id !== ev.id); renderEventList(); renderCalendar(); save(); };
    it.appendChild(main); it.appendChild(x); box.appendChild(it);
  });
  refit("events");
}
function initEvents() {
  renderCalendar();
  $("#calPrev").onclick = () => { calM--; if (calM < 0) { calM = 11; calY--; } renderCalendar(); };
  $("#calNext").onclick = () => { calM++; if (calM > 11) { calM = 0; calY++; } renderCalendar(); };
  const add = () => { const title = $("#evTitle").value.trim(); if (!title || !selDate) return; data.events.push({ id: uid(), date: selDate, time: $("#evTime").value, title }); $("#evTitle").value = ""; $("#evTime").value = ""; renderEventList(); renderCalendar(); save(); };
  $("#evAdd").onclick = add; $("#evTitle").addEventListener("keydown", e => { if (e.key === "Enter") add(); });
}

/* ============ TIMER ============ */
const RING_C = 2 * Math.PI * 52;
let tRemain = 1500, tTotal = 1500, tRunning = false, tInt = null;
function fmtClock(s) { return `${pad(Math.floor(s / 60))}:${pad(s % 60)}`; }
function renderTimer() { $("#timerDisplay").textContent = fmtClock(Math.max(0, tRemain)); const frac = tTotal > 0 ? tRemain / tTotal : 0; $("#ringFg").style.strokeDashoffset = String(RING_C * (1 - frac)); }
function timerTick() { tRemain--; if (tRemain <= 0) { tRemain = 0; renderTimer(); stopTimer(); alertUser("Timer finished ⏱️", "Your countdown reached zero."); return; } renderTimer(); }
function startTimer() { if (tRemain <= 0) return; tRunning = true; $("#timerToggle").textContent = "Pause"; clearInterval(tInt); tInt = setInterval(timerTick, 1000); }
function stopTimer() { tRunning = false; $("#timerToggle").textContent = tRemain <= 0 ? "Start" : "Resume"; clearInterval(tInt); }
function resetTimer() { stopTimer(); tRemain = tTotal; $("#timerToggle").textContent = "Start"; renderTimer(); }
function setTimer(sec) { tTotal = sec; tRemain = sec; data.timer.dur = sec; stopTimer(); $("#timerToggle").textContent = "Start"; renderTimer(); save(); }
function initTimer() {
  tTotal = tRemain = data.timer.dur || 1500; renderTimer();
  $("#timerToggle").onclick = () => tRunning ? stopTimer() : startTimer();
  $("#timerReset").onclick = resetTimer;
  $$(".presets .chip[data-min]").forEach(b => b.onclick = () => setTimer(parseInt(b.dataset.min) * 60));
  $("#tPlus").onclick = () => setTimer(Math.min(360 * 60, tTotal + 60));
  $("#tMinus").onclick = () => setTimer(Math.max(60, tTotal - 60));
}

/* ============ STOPWATCH ============ */
let swElapsed = 0, swRunning = false, swInt = null, swStart = 0, laps = [];
function fmtSW(ms) { const t = Math.floor(ms / 100); const cs = t % 10, s = Math.floor(t / 10) % 60, m = Math.floor(t / 600); return `${pad(m)}:${pad(s)}.${cs}`; }
function renderSW() { $("#stopDisplay").textContent = fmtSW(swElapsed); }
function initStopwatch() {
  renderSW();
  $("#stopToggle").onclick = () => { if (swRunning) { swRunning = false; clearInterval(swInt); $("#stopToggle").textContent = "Resume"; } else { swRunning = true; swStart = Date.now() - swElapsed; swInt = setInterval(() => { swElapsed = Date.now() - swStart; renderSW(); }, 100); $("#stopToggle").textContent = "Stop"; } };
  $("#stopReset").onclick = () => { swRunning = false; clearInterval(swInt); swElapsed = 0; laps = []; renderSW(); $("#lapList").innerHTML = ""; $("#stopToggle").textContent = "Start"; refit("timer"); };
  $("#lapBtn").onclick = () => { if (!swElapsed) return; laps.unshift(fmtSW(swElapsed)); $("#lapList").innerHTML = laps.map((l, i) => `<div class="mini-item"><div class="mi-main">Lap ${laps.length - i} <small>${l}</small></div></div>`).join(""); refit("timer"); };
  $("#tabTimer").onclick = () => switchTimerTab(true);
  $("#tabStop").onclick = () => switchTimerTab(false);
}
function switchTimerTab(timer) { $("#tabTimer").classList.toggle("active", timer); $("#tabStop").classList.toggle("active", !timer); $("#timerView").hidden = !timer; $("#stopView").hidden = timer; $("#timerTitle").textContent = timer ? "TIMER" : "STOPWATCH"; refit("timer"); }

/* ============ TO-DO ============ */
function renderTodos() {
  const box = $("#todoList"); box.innerHTML = "";
  data.todos.forEach(t => {
    const it = document.createElement("div"); it.className = "mini-item" + (t.done ? " done" : "");
    const ck = document.createElement("div"); ck.className = "check"; ck.textContent = "✓"; ck.onclick = () => { t.done = !t.done; renderTodos(); save(); };
    const main = document.createElement("div"); main.className = "mi-main"; main.textContent = t.text;
    const x = document.createElement("div"); x.className = "mi-x"; x.textContent = "✕"; x.onclick = () => { data.todos = data.todos.filter(y => y.id !== t.id); renderTodos(); save(); };
    it.appendChild(ck); it.appendChild(main); it.appendChild(x); box.appendChild(it);
  });
  refit("todo");
}
function initTodos() { const add = () => { const v = $("#todoText").value.trim(); if (!v) return; data.todos.push({ id: uid(), text: v, done: false }); $("#todoText").value = ""; renderTodos(); save(); }; $("#todoAdd").onclick = add; $("#todoText").addEventListener("keydown", e => { if (e.key === "Enter") add(); }); }

/* ============ CALCULATOR ============ */
let calcStr = "0";
function initCalc() {
  const disp = $("#calcDisplay");
  const upd = () => disp.textContent = calcStr.length > 14 ? String(Number(calcStr)) : calcStr;
  $("#calcGrid").addEventListener("click", (e) => {
    const b = e.target.closest(".calc"); if (!b) return; const k = b.dataset.k;
    if (k === "C") calcStr = "0";
    else if (k === "=") { try { const r = evalCalc(calcStr); calcStr = (r === null) ? "Error" : String(r); } catch { calcStr = "Error"; } }
    else if (k === "±") calcStr = calcStr.startsWith("-") ? calcStr.slice(1) : (calcStr !== "0" ? "-" + calcStr : calcStr);
    else if (k === "%") { try { calcStr = String(Number(evalCalc(calcStr)) / 100); } catch {} }
    else if ("+-*/".includes(k)) { if (calcStr === "Error") calcStr = "0"; if (/[+\-*/]$/.test(calcStr)) calcStr = calcStr.slice(0, -1) + k; else calcStr += k; }
    else { if (calcStr === "0" && k !== ".") calcStr = k; else if (calcStr === "Error") calcStr = k; else calcStr += k; }
    upd();
  });
}
function evalCalc(str) { if (!/^[-+*/.()\d\s]+$/.test(str)) return null; str = str.replace(/[+\-*/.]+$/, ""); if (!str) return 0; const r = Function('"use strict";return (' + str + ')')(); return (typeof r === "number" && isFinite(r)) ? Math.round(r * 1e10) / 1e10 : null; }

/* ============ SCHEDULER ============ */
function startSchedulers() {
  setInterval(() => {
    const now = Date.now(); let changed = false;
    data.reminders.forEach(r => { if (!r.fired && r.at <= now) { r.fired = true; changed = true; alertUser("Reminder 🔔", r.text); } });
    if (changed) { renderReminders(); save(); }
  }, 5000);
}

/* ============ TITLE BAR / WINDOW ============ */
async function syncPin() { try { $("#pinBtn").classList.toggle("active", !!(await window.api.getPin())); } catch (e) {} }
function initTitlebar() {
  $("#pinBtn").onclick = async () => { const cur = $("#pinBtn").classList.contains("active"); await window.api.setPin(!cur); $("#pinBtn").classList.toggle("active", !cur); };
  $("#minBtn").onclick = () => window.api.minimize();
  $("#maxBtn").onclick = () => window.api.maxToggle();
  $("#closeBtn").onclick = () => window.api.hide();
}

/* ============ WINDOW RESIZE GRIP ============ */
function initGrip() {
  const grip = $("#grip"); let sx, sy, sw, sh, dragging = false, raf = null, pw, ph;
  grip.addEventListener("pointerdown", (e) => { dragging = true; grip.setPointerCapture(e.pointerId); sx = e.screenX; sy = e.screenY; sw = window.innerWidth; sh = window.innerHeight; e.preventDefault(); });
  grip.addEventListener("pointermove", (e) => { if (!dragging) return; pw = sw + (e.screenX - sx); ph = sh + (e.screenY - sy); if (raf) return; raf = requestAnimationFrame(() => { raf = null; window.api.setSize(pw, ph); }); });
  const end = (e) => { if (dragging) { dragging = false; try { grip.releasePointerCapture(e.pointerId); } catch (er) {} } };
  grip.addEventListener("pointerup", end); grip.addEventListener("pointercancel", end);
}

/* ============ UTIL ============ */
function formatTime(t) { try { const [h, m] = t.split(":"); const d = new Date(); d.setHours(+h, +m); return d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }); } catch { return t; } }

/* ============ BOOT ============ */
async function boot() {
  let loaded = null;
  try { loaded = await window.api.load(); } catch (e) {}
  if (loaded && typeof loaded === "object") {
    data = Object.assign(data, loaded);
    data.timer = data.timer || { dur: 1500 };
    ["notes", "reminders", "events", "todos"].forEach(k => { data[k] = data[k] || []; });
    const defs = { opacity: 0.55, clock: true, panels: { notes: true, reminders: true, events: true, timer: true, todo: true, calc: true }, min: {}, max: null, weights: {}, split: 0.56, layout: { left: ["notes"], right: ["reminders", "events", "timer", "todo", "calc"] } };
    data.settings = Object.assign(defs, data.settings || {});
    data.settings.panels = Object.assign({ notes: true, reminders: true, events: true, timer: true, todo: true, calc: true }, data.settings.panels || {});
    data.settings.min = data.settings.min || {}; data.settings.weights = data.settings.weights || {};
    if (!data.settings.layout || !data.settings.layout.left) data.settings.layout = defs.layout;
    // make sure every panel key appears somewhere
    const inLayout = new Set([...(data.settings.layout.left || []), ...(data.settings.layout.right || [])]);
    ALL_KEYS.forEach(k => { if (!inLayout.has(k)) { const side = PANELS.find(p => p.key === k).side; data.settings.layout[side].push(k); } });
  }
  if (!data.notes.length) {
    data.notes.push({ id: uid(), title: "Welcome 👋", html: "Your notes, reminders, events and timers — all in one glass widget.<br><br>Saved <b>locally on this PC</b>. No internet, no accounts, no ads.<br><br>• <b>Drag any panel by its title</b> to move it anywhere<br>• Drag a panel's bottom edge to resize it<br>• Everything shrinks to fit — no scrolling to find things<br>• ⚙️ Settings for transparency &amp; panels", updated: Date.now() });
    data.activeNote = data.notes[0].id;
  }
  if (!data.activeNote && data.notes.length) data.activeNote = data.notes[0].id;

  // wrap non-notes panel content in a .fit scaler + add resize handle to every panel
  PANELS.forEach(p => {
    const el = panelEl(p.key), body = el.querySelector(".panel-body");
    if (p.key !== "notes" && !body.querySelector(".fit")) {
      const fit = document.createElement("div"); fit.className = "fit";
      while (body.firstChild) fit.appendChild(body.firstChild);
      body.appendChild(fit);
    }
    const rh = document.createElement("div"); rh.className = "panel-resize"; rh.title = "Drag to resize"; el.appendChild(rh);
  });

  arrangeFromLayout();
  initTitlebar(); initSettings(); initPanelControls(); initReorder(); initWeights(); initDivider();
  renderClock(); setInterval(renderClock, 1000);
  initNotes(); loadNoteIntoEditor();
  initReminders(); renderReminders();
  initEvents();
  initTimer(); initStopwatch();
  initTodos(); renderTodos();
  initCalc();
  startSchedulers();
  initGrip();
  setupFit();
  await syncPin();
  applyLayout();
  const t = new Date(); $("#remDate").value = ymd(t.getFullYear(), t.getMonth(), t.getDate());
}
document.addEventListener("DOMContentLoaded", boot);

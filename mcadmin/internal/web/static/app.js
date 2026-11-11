// FunCore admin panel. Plain JavaScript, no build step. Everything shown from the server (file names, log lines,
// account names) goes in as text, never as HTML.
"use strict";

const $ = (sel) => document.querySelector(sel);
const state = { me: null, tab: "console", root: "data", path: "", roots: [], editing: null, dirty: false };

// ---------------------------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------------------------

/** Makes an element: el("button", { class: "small", onclick: f }, "text", child, ...). */
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if (k === "class") node.className = v;
    else node.setAttribute(k, v === true ? "" : v);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function api(method, url, body) {
  const options = { method, credentials: "same-origin", headers: {} };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const res = await fetch(url, options);
  let data = null;
  try { data = await res.json(); } catch { /* empty */ }
  if (res.status === 401 && !url.endsWith("/api/login")) {
    showLogin();
    throw new ApiError(401, (data && data.error) || "log in again");
  }
  if (res.status === 403 && data && data.error === "choose a new password first") {
    showMustChange();
    throw new ApiError(403, data.error);
  }
  if (!res.ok) throw new ApiError(res.status, (data && data.error) || res.statusText);
  return data;
}

function toast(message, kind = "") {
  const t = el("div", { class: "toast " + kind, role: "status" }, message);
  $("#toasts").append(t);
  setTimeout(() => t.remove(), kind === "bad" ? 7000 : 3500);
}

function fail(err) {
  if (err instanceof ApiError && (err.status === 401 || (err.status === 403 && err.message === "choose a new password first"))) return;
  toast(err.message || String(err), "bad");
}

function size(bytes) {
  if (bytes < 1024) return bytes + " B";
  const units = ["KB", "MB", "GB", "TB"];
  let v = bytes, i = -1;
  do { v /= 1024; i++; } while (v >= 1024 && i < units.length - 1);
  return v.toFixed(v < 10 ? 1 : 0) + " " + units[i];
}

function when(iso) {
  const d = new Date(iso);
  if (isNaN(d)) return "";
  return d.toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function joinPath(dir, name) { return dir ? dir + "/" + name : name; }

// ---------------------------------------------------------------------------------------------------------------
// Dialogs: confirm, ask for text, or show a message
// ---------------------------------------------------------------------------------------------------------------

function dialog({ title, text = "", input = null, ok = "OK", danger = false, cancel = true, secret = null }) {
  return new Promise((resolve) => {
    const modal = $("#modal"), form = $("#modal-form"), field = $("#modal-input"), okBtn = $("#modal-ok");
    $("#modal-title").textContent = title;
    const p = $("#modal-text");
    p.textContent = text;
    if (secret) p.append(el("span", { class: "secret" }, secret));
    field.hidden = input == null;
    field.value = input ?? "";
    okBtn.textContent = ok;
    okBtn.className = danger ? "danger" : "primary";
    $("#modal-cancel").hidden = !cancel;
    modal.hidden = false;
    (input != null ? field : okBtn).focus();
    if (input != null) {
      const dot = field.value.lastIndexOf(".");
      field.setSelectionRange(0, dot > 0 ? dot : field.value.length);
    }
    const done = (value) => {
      modal.hidden = true;
      form.removeEventListener("submit", onSubmit);
      $("#modal-cancel").removeEventListener("click", onCancel);
      document.removeEventListener("keydown", onKey);
      resolve(value);
    };
    const onSubmit = (e) => { e.preventDefault(); done(input != null ? field.value.trim() : true); };
    const onCancel = () => done(null);
    const onKey = (e) => { if (e.key === "Escape") done(null); };
    form.addEventListener("submit", onSubmit);
    $("#modal-cancel").addEventListener("click", onCancel);
    document.addEventListener("keydown", onKey);
  });
}

// ---------------------------------------------------------------------------------------------------------------
// Screens: login, first-login password, the panel
// ---------------------------------------------------------------------------------------------------------------

function showScreen(id) {
  for (const s of ["login", "must-change", "app"]) $("#" + s).hidden = s !== id;
}

function showLogin() {
  stopConsole();
  state.me = null;
  showScreen("login");
  $("#login-form").password.value = "";
  $("#login-form").user.focus();
}

function showMustChange() {
  stopConsole();
  showScreen("must-change");
  $("#must-change-form").current.focus();
}

async function start() {
  try {
    state.me = await api("GET", "/api/me");
  } catch {
    return; // api() showed the login or password screen
  }
  if (state.me.mustChange) return showMustChange();
  showScreen("app");
  $("#me-name").textContent = state.me.name;
  $("#me-role").textContent = state.me.role;
  $("#me-role").className = "tag " + (state.me.role === "owner" ? "owner" : "");
  for (const b of document.querySelectorAll(".owner-only")) b.hidden = state.me.role !== "owner";
  pollPower();
  openTab(location.hash.slice(1) || "console");
}

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = e.target, err = $("#login-error");
  err.hidden = true;
  f.querySelector("button").disabled = true;
  try {
    await api("POST", "/api/login", { user: f.user.value, password: f.password.value });
    f.password.value = "";
    await start();
  } catch (ex) {
    err.textContent = ex.message;
    err.hidden = false;
  } finally {
    f.querySelector("button").disabled = false;
  }
});

$("#must-change-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = e.target, err = $("#must-change-error");
  err.hidden = true;
  if (f.next.value !== f.again.value) {
    err.textContent = "The new passwords don't match.";
    err.hidden = false;
    return;
  }
  try {
    await api("POST", "/api/account/password", { current: f.current.value, next: f.next.value });
    f.reset();
    toast("Password saved. Welcome!", "good");
    await start();
  } catch (ex) {
    err.textContent = ex.message;
    err.hidden = false;
  }
});

document.addEventListener("click", async (e) => {
  if (e.target.closest("[data-action=logout]")) {
    try { await api("POST", "/api/logout"); } catch { /* logged out anyway */ }
    showLogin();
  }
});

// ---------------------------------------------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------------------------------------------

async function openTab(tab) {
  if (!["console", "files", "users", "audit", "account"].includes(tab)) tab = "console";
  if ((tab === "users" || tab === "audit") && state.me.role !== "owner") tab = "console";
  if (state.tab === "files" && tab !== "files" && state.editing && state.dirty) {
    if (!(await dialog({ title: "Leave without saving?", text: "Your changes to " + state.editing.path + " aren't saved.", ok: "Leave", danger: true }))) return;
    closeEditor(true);
  }
  state.tab = tab;
  history.replaceState(null, "", "#" + tab);
  for (const b of document.querySelectorAll("#tabs button")) b.classList.toggle("active", b.dataset.tab === tab);
  for (const s of document.querySelectorAll(".tab")) s.hidden = s.id !== "tab-" + tab;
  if (tab === "console") startConsole();
  if (tab === "files") loadFiles();
  if (tab === "users") loadUsers();
  if (tab === "audit") loadAudit();
}

$("#tabs").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-tab]");
  if (b) openTab(b.dataset.tab);
});

// ---------------------------------------------------------------------------------------------------------------
// Server status and power
// ---------------------------------------------------------------------------------------------------------------

let powerTimer = null;

async function pollPower() {
  clearTimeout(powerTimer);
  if (!state.me) return;
  try {
    const s = await api("GET", "/api/power");
    const pill = $("#status");
    let label, cls;
    if (s.busy) { label = { start: "Starting…", stop: "Stopping…", restart: "Restarting…" }[s.busy] || "Working…"; cls = "busy"; }
    else if (s.state === "running") {
      cls = s.health === "starting" ? "busy" : "running";
      label = s.health === "starting" ? "Starting up…" : s.health === "unhealthy" ? "Running (unhealthy)" : "Online";
    } else if (s.state === "restarting") { label = "Restarting…"; cls = "busy"; }
    else if (s.state === "exited" || s.state === "created" || s.state === "dead") { label = "Offline"; cls = "stopped"; }
    else { label = s.state === "unknown" ? "Unknown" : s.state; cls = ""; }
    pill.className = "pill " + cls;
    $("#status-text").textContent = label;
    pill.title = s.lastError || (s.startedAt && s.state === "running" ? "Up since " + when(s.startedAt) : "");
    const running = s.state === "running" || s.state === "restarting";
    $("#btn-start").disabled = !!s.busy || running || s.state === "unknown";
    $("#btn-stop").disabled = !!s.busy || !running;
    $("#btn-restart").disabled = !!s.busy || !running;
  } catch { /* shown on the next poll */ }
  powerTimer = setTimeout(pollPower, 3000);
}

document.addEventListener("click", async (e) => {
  const b = e.target.closest("button[data-power]");
  if (!b) return;
  const action = b.dataset.power;
  const words = {
    start: ["Start the server?", "It takes a minute or so to be joinable.", "Start"],
    restart: ["Restart the server?", "Players get a 10 second warning, the world saves, and it comes back in a minute or two.", "Restart"],
    stop: ["Stop the server?", "Players get a 10 second warning and the world saves. It stays off until someone starts it.", "Stop"],
  }[action];
  if (!(await dialog({ title: words[0], text: words[1], ok: words[2], danger: action !== "start" }))) return;
  try {
    await api("POST", "/api/power/" + action);
    toast(words[2] + " requested.", "good");
  } catch (err) { fail(err); }
  pollPower();
});

// ---------------------------------------------------------------------------------------------------------------
// Console
// ---------------------------------------------------------------------------------------------------------------

let stream = null;
const MAX_LINES = 2000;

function lineClass(line) {
  if (line.startsWith("»")) return "panel";
  if (line.startsWith("────")) return "divider";
  if (/\b(ERROR|SEVERE)\b|Exception/.test(line)) return "err";
  if (/\bWARN(ING)?\b/.test(line)) return "warn";
  if (/\]: <[^>]+> /.test(line)) return "chat";
  return "";
}

function addLine(line) {
  const log = $("#console-log");
  const stick = $("#autoscroll").checked;
  log.append(el("div", { class: lineClass(line) }, line));
  while (log.childElementCount > MAX_LINES) log.firstElementChild.remove();
  if (stick) log.scrollTop = log.scrollHeight;
}

function startConsole() {
  if (stream) return;
  $("#console-log").replaceChildren();
  stream = new EventSource("/api/console/stream");
  stream.onmessage = (e) => addLine(JSON.parse(e.data));
  stream.onerror = async () => {
    // It reconnects by itself; if the login ended, stop and show the login instead.
    try { await api("GET", "/api/me"); } catch { stopConsole(); }
  };
  setTimeout(() => $("#console-cmd").focus(), 0);
}

function stopConsole() {
  if (stream) { stream.close(); stream = null; }
}

const history_ = [];
let historyAt = 0;

$("#console-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = $("#console-cmd"), cmd = input.value.trim();
  if (!cmd) return;
  if (history_[history_.length - 1] !== cmd) history_.push(cmd);
  historyAt = history_.length;
  input.value = "";
  try { await api("POST", "/api/console/command", { command: cmd }); }
  catch (err) { fail(err); }
});

$("#console-cmd").addEventListener("keydown", (e) => {
  if (e.key === "ArrowUp" && historyAt > 0) { historyAt--; e.target.value = history_[historyAt]; e.preventDefault(); }
  if (e.key === "ArrowDown") {
    historyAt = Math.min(history_.length, historyAt + 1);
    e.target.value = history_[historyAt] ?? "";
    e.preventDefault();
  }
});
$("#console-clear").addEventListener("click", () => $("#console-log").replaceChildren());
$("#console-log").addEventListener("scroll", (e) => {
  const log = e.target;
  $("#autoscroll").checked = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
});

// ---------------------------------------------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------------------------------------------

function q(params) { return new URLSearchParams(params).toString(); }

async function loadFiles() {
  if (state.editing) return;
  try {
    if (!state.roots.length) state.roots = await api("GET", "/api/files/roots");
    renderRoots();
    renderCrumbs();
    const entries = await api("GET", "/api/files/list?" + q({ root: state.root, path: state.path }));
    renderFiles(entries);
  } catch (err) {
    if (err.status === 404 && state.path) { state.path = ""; return loadFiles(); }
    fail(err);
  }
}

const ROOT_NOTES = {
  plugins: "Plugin jars live here. On every start the server copies them into Server › plugins, where each plugin's own " +
    "folder (its config) is. To remove a plugin, delete its jar here AND in Server › plugins, then restart.",
};

function renderRoots() {
  const note = $("#root-note");
  note.textContent = ROOT_NOTES[state.root] || "";
  note.hidden = !ROOT_NOTES[state.root];
  $("#roots").replaceChildren(...state.roots.map((r) =>
    el("button", { class: r.name === state.root ? "active" : "", onclick: () => { state.root = r.name; state.path = ""; loadFiles(); } }, r.label)));
}

function renderCrumbs() {
  const parts = state.path ? state.path.split("/") : [];
  const label = (state.roots.find((r) => r.name === state.root) || {}).label || state.root;
  const crumbs = [el("button", { onclick: () => { state.path = ""; loadFiles(); } }, label)];
  parts.forEach((part, i) => {
    crumbs.push(el("span", { class: "sep" }, "/"));
    crumbs.push(el("button", { onclick: () => { state.path = parts.slice(0, i + 1).join("/"); loadFiles(); } }, part));
  });
  $("#crumbs").replaceChildren(...crumbs);
}

const TEXT = /\.(ya?ml|json|properties|txt|log|cfg|conf|toml|ini|md|csv|sk|mcmeta|js|sh|xml|html|css|lang|nbs\.txt)$/i;

function renderFiles(entries) {
  const body = $("#files-body");
  const rows = [];
  if (state.path) {
    rows.push(el("tr", {}, el("td", { class: "name", colspan: 4 },
      el("button", { onclick: () => { state.path = state.path.split("/").slice(0, -1).join("/"); loadFiles(); } },
        el("span", { class: "icon dir" }, "↰"), ".."))));
  }
  for (const f of entries) {
    const path = joinPath(state.path, f.name);
    const open = f.dir ? () => { state.path = path; loadFiles(); } : () => openFile(path, f);
    rows.push(el("tr", {},
      el("td", { class: "name" }, el("button", { onclick: open, title: f.dir ? "Open folder" : "Open" },
        el("span", { class: "icon" + (f.dir ? " dir" : "") }, f.dir ? "▸" : "·"), f.name, f.link ? " ↪" : "")),
      el("td", { class: "num muted" }, f.dir ? "" : size(f.size)),
      el("td", { class: "when" }, when(f.modified)),
      el("td", { class: "acts" },
        f.dir ? null : el("button", { class: "small ghost", onclick: () => download(path) }, "Download"),
        el("button", { class: "small ghost", onclick: () => renameItem(path, f) }, "Rename"),
        el("button", { class: "small danger", onclick: () => deleteItem(path, f) }, "Delete"))));
  }
  body.replaceChildren(...rows);
  $("#files-empty").hidden = entries.length > 0;
}

function download(path) {
  const a = el("a", { href: "/api/files/download?" + q({ root: state.root, path }), download: "" });
  document.body.append(a);
  a.click();
  a.remove();
}

async function openFile(path, f) {
  if (!TEXT.test(f.name) && f.size > 0) {
    const choice = await dialog({ title: f.name, text: "This doesn't look like a text file. Download it, or try opening it in the editor?", ok: "Download" });
    if (choice) return download(path);
    if (!(await dialog({ title: "Open in the editor?", text: "Only text files can be edited.", ok: "Try anyway" }))) return;
  }
  try {
    const { content } = await api("GET", "/api/files/read?" + q({ root: state.root, path }));
    state.editing = { root: state.root, path };
    state.dirty = false;
    $("#editor-path").textContent = path;
    $("#editor-dirty").hidden = true;
    $("#editor-text").value = content;
    $("#files-browser").hidden = true;
    $("#editor").hidden = false;
    $("#editor-text").focus();
    $("#editor-text").setSelectionRange(0, 0);
    $("#editor-text").scrollTop = 0;
  } catch (err) { fail(err); }
}

async function closeEditor(force = false) {
  if (!force && state.dirty) {
    if (!(await dialog({ title: "Close without saving?", text: "Your changes to " + state.editing.path + " will be lost.", ok: "Close", danger: true }))) return;
  }
  state.editing = null;
  state.dirty = false;
  $("#editor").hidden = true;
  $("#files-browser").hidden = false;
  loadFiles();
}

async function saveEditor() {
  if (!state.editing) return;
  try {
    await api("POST", "/api/files/write", { root: state.editing.root, path: state.editing.path, content: $("#editor-text").value });
    state.dirty = false;
    $("#editor-dirty").hidden = true;
    toast("Saved " + state.editing.path, "good");
  } catch (err) { fail(err); }
}

$("#editor-close").addEventListener("click", () => closeEditor());
$("#editor-save").addEventListener("click", saveEditor);
$("#editor-text").addEventListener("input", () => { state.dirty = true; $("#editor-dirty").hidden = false; });
$("#editor-text").addEventListener("keydown", (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") { e.preventDefault(); saveEditor(); }
  if (e.key === "Tab" && !e.ctrlKey && !e.altKey) {
    // YAML wants spaces: Tab types two of them.
    e.preventDefault();
    const t = e.target, s = t.selectionStart;
    t.setRangeText("  ", s, t.selectionEnd, "end");
    t.dispatchEvent(new Event("input"));
  }
});
window.addEventListener("beforeunload", (e) => { if (state.editing && state.dirty) e.preventDefault(); });

async function renameItem(path, f) {
  const name = await dialog({ title: "Rename", text: "New name for " + f.name + ":", input: f.name, ok: "Rename" });
  if (!name || name === f.name) return;
  try {
    await api("POST", "/api/files/rename", { root: state.root, from: path, to: joinPath(state.path, name) });
    toast("Renamed to " + name, "good");
    loadFiles();
  } catch (err) { fail(err); }
}

async function deleteItem(path, f) {
  const ok = await dialog({
    title: "Delete " + f.name + "?",
    text: f.dir ? "This deletes the folder and EVERYTHING in it. It can't be undone." : "This can't be undone.",
    ok: "Delete", danger: true,
  });
  if (!ok) return;
  try {
    await api("POST", "/api/files/delete", { root: state.root, path });
    toast("Deleted " + f.name, "good");
    loadFiles();
  } catch (err) { fail(err); }
}

$("#btn-newfolder").addEventListener("click", async () => {
  const name = await dialog({ title: "New folder", text: "Name:", input: "", ok: "Create" });
  if (!name) return;
  try { await api("POST", "/api/files/mkdir", { root: state.root, path: joinPath(state.path, name) }); loadFiles(); }
  catch (err) { fail(err); }
});

$("#btn-newfile").addEventListener("click", async () => {
  const name = await dialog({ title: "New file", text: "Name (e.g. config.yml):", input: "", ok: "Create" });
  if (!name) return;
  const path = joinPath(state.path, name);
  try {
    const existing = await api("GET", "/api/files/list?" + q({ root: state.root, path: state.path }));
    if (existing.some((f) => f.name.toLowerCase() === name.toLowerCase())) return toast("There's already something called " + name, "bad");
    await api("POST", "/api/files/write", { root: state.root, path, content: "" });
    await openFile(path, { name, size: 0 });
  } catch (err) { fail(err); }
});

$("#btn-refresh").addEventListener("click", loadFiles);
$("#btn-upload").addEventListener("click", () => $("#upload-input").click());
$("#upload-input").addEventListener("change", (e) => { upload([...e.target.files]); e.target.value = ""; });

const zone = $("#dropzone");
zone.addEventListener("dragover", (e) => { e.preventDefault(); zone.classList.add("over"); });
zone.addEventListener("dragleave", (e) => { if (!zone.contains(e.relatedTarget)) zone.classList.remove("over"); });
zone.addEventListener("drop", (e) => {
  e.preventDefault();
  zone.classList.remove("over");
  const list = [...e.dataTransfer.files];
  if (list.length) upload(list);
});

async function upload(list) {
  if (!list.length) return;
  const root = state.root, dir = state.path;
  let existing = [];
  try { existing = await api("GET", "/api/files/list?" + q({ root, path: dir })); } catch (err) { return fail(err); }
  const clash = list.filter((f) => existing.some((x) => x.name === f.name));
  if (clash.length && !(await dialog({
    title: "Replace files?", text: clash.map((f) => f.name).join("\n") + "\n\nalready exist here and will be replaced.", ok: "Replace", danger: true,
  }))) return;
  for (const file of list) {
    const bar = el("progress", { max: 100, value: 0 });
    const row = el("div", { class: "upload" }, el("span", {}, file.name), bar, el("span", { class: "muted" }, size(file.size)));
    $("#uploads").append(row);
    try {
      await new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/files/upload?" + q({ root, path: dir }));
        xhr.upload.onprogress = (e) => { if (e.lengthComputable) bar.value = (e.loaded / e.total) * 100; };
        xhr.onload = () => {
          let data = {};
          try { data = JSON.parse(xhr.responseText); } catch { /* none */ }
          if (xhr.status === 200) resolve();
          else if (xhr.status === 401) { showLogin(); reject(new ApiError(401, "log in again")); }
          else reject(new ApiError(xhr.status, data.error || xhr.statusText));
        };
        xhr.onerror = () => reject(new Error("the upload was interrupted"));
        const form = new FormData();
        form.append("file", file, file.name);
        xhr.send(form);
      });
      toast("Uploaded " + file.name, "good");
    } catch (err) { fail(err); }
    row.remove();
  }
  if (state.root === root && state.path === dir) loadFiles();
}

// ---------------------------------------------------------------------------------------------------------------
// Accounts (owners)
// ---------------------------------------------------------------------------------------------------------------

async function loadUsers() {
  try {
    const users = await api("GET", "/api/users");
    $("#users-body").replaceChildren(...users.map((u) => {
      const self = u.name.toLowerCase() === state.me.name.toLowerCase();
      return el("tr", {},
        el("td", {}, u.name, self ? el("span", { class: "muted" }, " (you)") : null,
          u.mustChange ? el("span", { class: "tag warn", title: "Hasn't chosen their own password yet" }, " new") : null),
        el("td", {}, el("span", { class: "tag " + (u.role === "owner" ? "owner" : "") }, u.role)),
        el("td", { class: "when" }, when(u.created)),
        el("td", { class: "acts" },
          el("button", { class: "small ghost", onclick: () => setRole(u, u.role === "owner" ? "admin" : "owner") },
            u.role === "owner" ? "Make admin" : "Make owner"),
          el("button", { class: "small ghost", onclick: () => resetPassword(u) }, "Reset password"),
          self ? null : el("button", { class: "small danger", onclick: () => removeUser(u) }, "Remove")));
    }));
  } catch (err) { fail(err); }
}

$("#add-user").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = e.target, name = f.name.value.trim();
  try {
    const { password } = await api("POST", "/api/users", { name, role: f.role.value });
    f.reset();
    loadUsers();
    await dialog({ title: "Account added", text: "Give " + name + " this one-time password. It's shown only now; they choose their own when they log in.", secret: password, ok: "Done", cancel: false });
  } catch (err) { fail(err); }
});

async function setRole(u, role) {
  if (!(await dialog({ title: "Make " + u.name + " " + (role === "owner" ? "an owner" : "an admin") + "?",
    text: role === "owner" ? "Owners can add and remove accounts and see the activity log." : "They'll lose access to accounts and the activity log.", ok: "Change" }))) return;
  try { await api("POST", "/api/users/" + encodeURIComponent(u.name) + "/role", { role }); loadUsers(); }
  catch (err) { fail(err); }
}

async function resetPassword(u) {
  if (!(await dialog({ title: "Reset " + u.name + "'s password?", text: "They're logged out everywhere and get a new one-time password.", ok: "Reset", danger: true }))) return;
  try {
    const { password } = await api("POST", "/api/users/" + encodeURIComponent(u.name) + "/reset", {});
    loadUsers();
    await dialog({ title: "New password for " + u.name, text: "Give them this one-time password. It's shown only now.", secret: password, ok: "Done", cancel: false });
  } catch (err) { fail(err); }
}

async function removeUser(u) {
  if (!(await dialog({ title: "Remove " + u.name + "?", text: "They're logged out and can't log in again.", ok: "Remove", danger: true }))) return;
  try { await api("DELETE", "/api/users/" + encodeURIComponent(u.name)); toast("Removed " + u.name, "good"); loadUsers(); }
  catch (err) { fail(err); }
}

// ---------------------------------------------------------------------------------------------------------------
// Activity log (owners)
// ---------------------------------------------------------------------------------------------------------------

async function loadAudit() {
  try {
    const entries = await api("GET", "/api/audit");
    $("#audit-body").replaceChildren(...entries.map((a) => el("tr", {},
      el("td", { class: "when" }, when(a.time)),
      el("td", { class: "gold" }, a.user),
      el("td", {}, a.action),
      el("td", { class: "mono" }, a.detail || ""),
      el("td", { class: "muted" }, a.ip))));
  } catch (err) { fail(err); }
}
$("#audit-refresh").addEventListener("click", loadAudit);

// ---------------------------------------------------------------------------------------------------------------
// My account
// ---------------------------------------------------------------------------------------------------------------

$("#password-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = e.target;
  if (f.next.value !== f.again.value) return toast("The new passwords don't match.", "bad");
  try {
    await api("POST", "/api/account/password", { current: f.current.value, next: f.next.value });
    f.reset();
    toast("Password changed.", "good");
  } catch (err) { fail(err); }
});

start();

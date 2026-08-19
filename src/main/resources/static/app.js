(() => {
  "use strict";

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const state = {
    mode: "chat",
    chatId: crypto.randomUUID(),
    messages: [],
    busy: false,
    selectedFile: null,
    conversations: loadJson("suvia-conversations", []),
  };

  const modeMeta = {
    chat: { label: "通用模型", endpoint: "ai/app/chat/sync" },
    rag: { label: "混合知识库检索", endpoint: "ai/app/chatRag/sync" },
    agent: { label: "多工具深度研究", endpoint: "ai/app/manus/sync" },
  };

  const elements = {
    messages: $("#messages"), welcome: $("#welcome"), input: $("#messageInput"), form: $("#chatForm"),
    send: $("#sendButton"), charCount: $("#charCount"), history: $("#historyList"), modeHint: $("#modeHint"),
    chatView: $("#chatView"), libraryView: $("#libraryView"), sidebar: $("#sidebar"), scrim: $("#sidebarScrim"),
    modal: $("#uploadModal"), fileInput: $("#fileInput"), selectedFile: $("#selectedFile"),
    selectedFileName: $("#selectedFileName"), selectedFileSize: $("#selectedFileSize"), uploadButton: $("#uploadSubmitButton"),
    progress: $("#uploadProgress"), progressBar: $("#progressBar"), uploadPercent: $("#uploadPercent"), uploadStatus: $("#uploadStatus"),
  };

  function loadJson(key, fallback) {
    try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
  }
  function saveConversations() { localStorage.setItem("suvia-conversations", JSON.stringify(state.conversations.slice(0, 18))); }
  function formatTime(date = new Date()) { return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }); }
  function escapeHtml(value = "") { return value.replace(/[&<>'"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[c]); }

  function renderMarkdown(raw) {
    let text = escapeHtml(raw || "");
    const codeBlocks = [];
    text = text.replace(/```([\w-]*)\n([\s\S]*?)```/g, (_, lang, code) => {
      const token = `%%CODEBLOCK${codeBlocks.length}%%`;
      codeBlocks.push(`<pre><code data-language="${lang}">${code.trim()}</code></pre>`);
      return token;
    });
    text = text.replace(/^### (.+)$/gm, "<h3>$1</h3>").replace(/^## (.+)$/gm, "<h2>$1</h2>").replace(/^# (.+)$/gm, "<h1>$1</h1>");
    text = text.replace(/^&gt; (.+)$/gm, "<blockquote>$1</blockquote>");
    text = text.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>").replace(/`([^`]+)`/g, "<code>$1</code>");
    text = text.replace(/(^|\s)(https?:\/\/[^\s<]+)/g, '$1<a href="$2" target="_blank" rel="noopener noreferrer">$2</a>');
    const lines = text.split("\n");
    let html = "", inList = false, listType = "";
    for (const line of lines) {
      const ul = line.match(/^[-*] (.+)$/), ol = line.match(/^\d+\. (.+)$/);
      const nextType = ul ? "ul" : ol ? "ol" : "";
      if (nextType) {
        if (!inList || listType !== nextType) { if (inList) html += `</${listType}>`; html += `<${nextType}>`; inList = true; listType = nextType; }
        html += `<li>${(ul || ol)[1]}</li>`;
      } else {
        if (inList) { html += `</${listType}>`; inList = false; }
        if (!line.trim()) continue;
        html += /^<(h\d|blockquote|pre)/.test(line) ? line : `<p>${line}</p>`;
      }
    }
    if (inList) html += `</${listType}>`;
    codeBlocks.forEach((block, index) => { html = html.replace(`<p>%%CODEBLOCK${index}%%</p>`, block).replace(`%%CODEBLOCK${index}%%`, block); });
    return html;
  }

  function addMessage(role, content, options = {}) {
    elements.welcome?.remove();
    const article = document.createElement("article");
    article.className = `message ${role}`;
    const name = role === "user" ? "你" : "Suvia";
    const avatar = role === "user" ? "YOU" : "S";
    article.innerHTML = `<div class="message-head"><span class="message-avatar">${avatar}</span><strong>${name}</strong><span class="message-time">${formatTime()}</span></div><div class="message-body">${options.typing ? '<div class="typing"><span></span><span></span><span></span></div>' : renderMarkdown(content)}</div>`;
    elements.messages.append(article);
    elements.messages.scrollTop = elements.messages.scrollHeight;
    if (!options.typing) state.messages.push({ role, content, time: Date.now() });
    return article;
  }

  function setBusy(value) {
    state.busy = value;
    elements.send.disabled = value;
    elements.send.classList.toggle("loading", value);
    elements.input.disabled = value;
  }

  async function sendMessage(message) {
    if (!message.trim() || state.busy) return;
    const cleanMessage = message.trim();
    addMessage("user", cleanMessage);
    elements.input.value = "";
    resizeTextarea();
    updateCharCount();
    setBusy(true);
    const pending = addMessage("assistant", "", { typing: true });
    try {
      const meta = modeMeta[state.mode];
      const params = new URLSearchParams({ message: cleanMessage });
      if (state.mode !== "agent") params.set("chatId", state.chatId);
      const response = await fetch(`${meta.endpoint}?${params}`, { headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error(`服务返回 ${response.status}`);
      const payload = await response.json();
      if (payload.code !== 0) throw new Error(payload.message || "请求失败");
      pending.remove();
      addMessage("assistant", payload.data || "未收到有效回复。");
      persistCurrentConversation(cleanMessage);
    } catch (error) {
      pending.remove();
      const article = addMessage("assistant", `暂时无法完成请求：${error.message}\n\n请确认后端服务、模型 API 和数据库均已连接。`);
      $(".message-body", article).classList.add("error-text");
      showToast(error.message, true);
    } finally { setBusy(false); elements.input.focus(); }
  }

  function persistCurrentConversation(firstMessage) {
    const existing = state.conversations.find(item => item.id === state.chatId);
    const conversation = { id: state.chatId, title: existing?.title || firstMessage.slice(0, 22), mode: state.mode, messages: state.messages, updatedAt: Date.now() };
    state.conversations = [conversation, ...state.conversations.filter(item => item.id !== state.chatId)];
    saveConversations(); renderHistory();
  }

  function renderHistory() {
    elements.history.innerHTML = "";
    if (!state.conversations.length) { elements.history.innerHTML = '<div class="empty-history">还没有历史会话<br>开始一次研究对话吧</div>'; return; }
    state.conversations.forEach(item => {
      const button = document.createElement("button");
      button.type = "button"; button.className = `history-item${item.id === state.chatId ? " active" : ""}`;
      button.textContent = item.title; button.title = item.title;
      button.addEventListener("click", () => loadConversation(item));
      elements.history.append(button);
    });
  }

  function loadConversation(item) {
    if (state.busy) return;
    state.chatId = item.id; state.mode = item.mode || "chat"; state.messages = [];
    elements.messages.innerHTML = "";
    (item.messages || []).forEach(message => addMessage(message.role, message.content));
    selectMode(state.mode); renderHistory(); showView("chat"); closeSidebar();
  }

  function newConversation() {
    if (state.busy) return;
    state.chatId = crypto.randomUUID(); state.messages = [];
    location.reload();
  }

  function selectMode(mode) {
    state.mode = mode;
    $$(".mode").forEach(button => button.classList.toggle("active", button.dataset.mode === mode));
    elements.modeHint.innerHTML = `<span></span> ${modeMeta[mode].label}`;
    elements.input.placeholder = mode === "rag" ? "基于知识库提问…" : mode === "agent" ? "描述一个需要多步完成的研究任务…" : "输入你的研究问题…";
  }

  function showView(view) {
    const library = view === "library";
    elements.chatView.classList.toggle("hidden", library);
    elements.libraryView.classList.toggle("hidden", !library);
    $$(".nav-item").forEach(button => button.classList.toggle("active", button.dataset.view === view));
  }

  function resizeTextarea() { elements.input.style.height = "auto"; elements.input.style.height = `${Math.min(elements.input.scrollHeight, 145)}px`; }
  function updateCharCount() { elements.charCount.textContent = `${elements.input.value.length} / 4000`; }
  function openSidebar() { elements.sidebar.classList.add("open"); elements.scrim.classList.remove("hidden"); }
  function closeSidebar() { elements.sidebar.classList.remove("open"); elements.scrim.classList.add("hidden"); }
  function openUpload() { elements.modal.classList.remove("hidden"); document.body.style.overflow = "hidden"; }
  function closeUpload() { if (elements.uploadButton.dataset.uploading) return; elements.modal.classList.add("hidden"); document.body.style.overflow = ""; resetFile(); }
  function formatBytes(bytes) { return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`; }

  function chooseFile(file) {
    if (!file) return;
    if (!file.name.toLowerCase().endsWith(".pdf")) { showToast("请选择 PDF 文件", true); return; }
    if (file.size > 50 * 1024 * 1024) { showToast("文件不能超过 50 MB", true); return; }
    state.selectedFile = file;
    elements.selectedFileName.textContent = file.name; elements.selectedFileSize.textContent = formatBytes(file.size);
    elements.selectedFile.classList.remove("hidden"); elements.uploadButton.disabled = false;
  }

  function resetFile() {
    state.selectedFile = null; elements.fileInput.value = ""; elements.selectedFile.classList.add("hidden");
    elements.progress.classList.add("hidden"); elements.uploadButton.disabled = true; elements.uploadButton.textContent = "上传并构建索引";
    elements.uploadButton.removeAttribute("data-uploading"); elements.uploadButton.removeAttribute("data-completed");
    elements.progressBar.style.width = "0"; elements.uploadStatus.style.color = ""; elements.uploadStatus.textContent = "正在上传并解析…";
  }

  function uploadFile() {
    if (!state.selectedFile) return;
    const data = new FormData(); data.append("objectName", state.selectedFile.name); data.append("file", state.selectedFile);
    const xhr = new XMLHttpRequest();
    elements.progress.classList.remove("hidden"); elements.uploadButton.disabled = true; elements.uploadButton.dataset.uploading = "true"; elements.uploadButton.textContent = "正在处理…";
    xhr.upload.addEventListener("progress", event => {
      const percent = event.lengthComputable ? Math.round(event.loaded / event.total * 90) : 45;
      elements.progressBar.style.width = `${percent}%`; elements.uploadPercent.textContent = `${percent}%`;
    });
    xhr.addEventListener("load", () => {
      try {
        const payload = JSON.parse(xhr.responseText);
        if (xhr.status < 200 || xhr.status >= 300 || payload.code !== 0) throw new Error(payload.message || `上传失败 (${xhr.status})`);
        elements.progressBar.style.width = "100%"; elements.uploadPercent.textContent = "100%"; elements.uploadStatus.textContent = "解析与索引已完成";
        elements.uploadButton.textContent = "完成"; delete elements.uploadButton.dataset.uploading; elements.uploadButton.disabled = false;
        elements.uploadButton.dataset.completed = "true";
      } catch (error) { uploadFailed(error.message); }
    });
    xhr.addEventListener("error", () => uploadFailed("无法连接上传服务"));
    xhr.open("POST", "rag/upload"); xhr.send(data);
  }

  function uploadFailed(message) {
    elements.uploadStatus.textContent = message; elements.uploadStatus.style.color = "#ad5148";
    elements.uploadButton.textContent = "重试"; elements.uploadButton.disabled = false; delete elements.uploadButton.dataset.uploading;
    showToast(message, true);
  }

  function showToast(message, error = false) {
    const toast = document.createElement("div"); toast.className = `toast${error ? " error" : ""}`; toast.textContent = message;
    $("#toastRegion").append(toast); setTimeout(() => toast.remove(), 4200);
  }

  elements.form.addEventListener("submit", event => { event.preventDefault(); sendMessage(elements.input.value); });
  elements.input.addEventListener("input", () => { resizeTextarea(); updateCharCount(); });
  elements.input.addEventListener("keydown", event => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); elements.form.requestSubmit(); } });
  $$(".suggestion").forEach(button => button.addEventListener("click", () => { selectMode("rag"); sendMessage(button.dataset.prompt); }));
  $$(".mode").forEach(button => button.addEventListener("click", () => selectMode(button.dataset.mode)));
  $$(".nav-item").forEach(button => button.addEventListener("click", () => { showView(button.dataset.view); closeSidebar(); }));
  $("#newChatButton").addEventListener("click", newConversation);
  $("#clearHistoryButton").addEventListener("click", () => { state.conversations = []; saveConversations(); renderHistory(); showToast("会话历史已清空"); });
  $("#menuButton").addEventListener("click", openSidebar); elements.scrim.addEventListener("click", closeSidebar);
  [$("#sourceButton"), $("#attachButton"), $("#libraryUploadButton")].forEach(button => button.addEventListener("click", openUpload));
  $("#closeModalButton").addEventListener("click", closeUpload); elements.modal.addEventListener("click", event => { if (event.target === elements.modal) closeUpload(); });
  elements.fileInput.addEventListener("change", event => chooseFile(event.target.files[0]));
  $("#removeFileButton").addEventListener("click", event => { event.preventDefault(); resetFile(); });
  elements.uploadButton.addEventListener("click", () => {
    if (elements.uploadButton.dataset.completed) { closeUpload(); showToast("文献已加入知识库"); return; }
    uploadFile();
  });
  const dropZone = $("#dropZone");
  ["dragenter", "dragover"].forEach(type => dropZone.addEventListener(type, event => { event.preventDefault(); dropZone.classList.add("dragging"); }));
  ["dragleave", "drop"].forEach(type => dropZone.addEventListener(type, event => { event.preventDefault(); dropZone.classList.remove("dragging"); }));
  dropZone.addEventListener("drop", event => chooseFile(event.dataTransfer.files[0]));
  $("#themeButton").addEventListener("click", () => { document.body.classList.toggle("dark"); localStorage.setItem("suvia-theme", document.body.classList.contains("dark") ? "dark" : "light"); });
  document.addEventListener("keydown", event => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") { event.preventDefault(); newConversation(); }
    if (event.key === "Escape") { closeUpload(); closeSidebar(); }
  });

  if (localStorage.getItem("suvia-theme") === "dark") document.body.classList.add("dark");
  renderHistory(); selectMode("chat"); updateCharCount();
})();

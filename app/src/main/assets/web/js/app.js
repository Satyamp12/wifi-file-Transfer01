/**
 * WiFi File Transfer - Web UI JavaScript
 * Receiver phone ke Chrome me chalega.
 * API endpoints match karte hain Android server (FileServer.kt) ke.
 */

// DOM
const uploadZone = document.getElementById("uploadZone");
const fileInput = document.getElementById("fileInput");
const sharedGrid = document.getElementById("sharedGrid");
const sentGrid = document.getElementById("sentGrid");
const sharedCount = document.getElementById("sharedCount");
const sentCount = document.getElementById("sentCount");
const uploadProgressList = document.getElementById("uploadProgressList");
const toastContainer = document.getElementById("toastContainer");

// ============================================
// TOAST
// ============================================
function showToast(msg, type = "info") {
  const t = document.createElement("div");
  t.className = "toast " + type;
  t.textContent = msg;
  toastContainer.appendChild(t);
  setTimeout(() => {
    t.style.opacity = "0";
    t.style.transform = "translateX(20px)";
    t.style.transition = "0.3s";
    setTimeout(() => t.remove(), 300);
  }, 3500);
}

// ============================================
// FILE TYPE ICON
// ============================================
function getFileIcon(mime, name) {
  const ext = (name || "").split(".").pop().toLowerCase();
  const videoExts = ["mp4","avi","mkv","mov","wmv","flv","webm","m4v","3gp"];
  const audioExts = ["mp3","wav","ogg","flac","aac","m4a"];
  const imgExts = ["jpg","jpeg","png","gif","bmp","webp","svg"];
  const docExts = ["pdf","doc","docx","txt","xls","xlsx","ppt","pptx","csv"];
  const zipExts = ["zip","rar","7z","tar","gz","iso"];
  const apkExts = ["apk","xapk","apks"];

  if (videoExts.includes(ext)) return "🎬";
  if (audioExts.includes(ext)) return "🎵";
  if (imgExts.includes(ext)) return "🖼️";
  if (docExts.includes(ext)) return "📄";
  if (zipExts.includes(ext)) return "🗜️";
  if (apkExts.includes(ext)) return "📦";
  return "📁";
}

function formatSize(bytes) {
  if (bytes <= 0) return "0 B";
  const u = ["B","KB","MB","GB","TB"];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), u.length - 1);
  return (bytes / Math.pow(1024, i)).toFixed(1) + " " + u[i];
}

// ============================================
// RENDER FILES
// ============================================
function renderFiles(grid, files, downloadUrl, deleteUrl, idParam) {
  if (!files || files.length === 0) {
    grid.innerHTML = '<div class="empty-state"><p>Koi file nahi hai</p></div>';
    return;
  }

  grid.innerHTML = files.map(f => {
    const icon = getFileIcon(f.mimeType, f.name);
    const ext = (f.name || "").split(".").pop().toUpperCase();
    const isImg = ["jpg","jpeg","png","gif","webp","bmp"].includes((f.name||"").split(".").pop().toLowerCase());
    const thumb = isImg
      ? `<img src="${downloadUrl}?${idParam}=${f.id}" alt="${f.name}" loading="lazy">`
      : `<span class="fc-icon">${icon}</span>`;
    const badge = ext && ext.length < 6 ? `<span class="fc-ext">${ext}</span>` : "";
    const dl = `${downloadUrl}?${idParam}=${encodeURIComponent(f.id)}`;
    const del = `${deleteUrl}?${idParam}=${encodeURIComponent(f.id)}`;

    return `
      <div class="file-card">
        <div class="fc-thumb">${thumb}${badge}</div>
        <div class="fc-info">
          <div class="fc-name" title="${f.name}">${f.name}</div>
          <div class="fc-meta">${f.sizeFormatted || formatSize(f.size)}</div>
        </div>
        <div class="fc-actions">
          <button class="fc-btn download" onclick="downloadFile('${dl}')">Download</button>
          <button class="fc-btn delete" onclick="deleteFile('${del}')">Delete</button>
        </div>
      </div>
    `;
  }).join("");
}

// ============================================
// LOAD FILES (polling)
// ============================================
async function loadSharedFiles() {
  try {
    const res = await fetch("/api/files");
    const data = await res.json();
    sharedCount.textContent = `${data.total} file${data.total !== 1 ? "s" : ""}`;
    renderFiles(sharedGrid, data.files, "/api/download", "/api/files", "id");
  } catch (e) {
    // silent fail - server might be restarting
  }
}

async function loadSentFiles() {
  try {
    const res = await fetch("/api/uploads");
    const data = await res.json();
    sentCount.textContent = `${data.total} file${data.total !== 1 ? "s" : ""}`;
    renderFiles(sentGrid, data.files, "/api/uploads/download", "/api/uploads", "name");
  } catch (e) {
    // silent fail
  }
}

// ============================================
// DOWNLOAD
// ============================================
function downloadFile(url) {
  const a = document.createElement("a");
  a.href = url;
  a.download = "";
  document.body.appendChild(a);
  a.click();
  a.remove();
  showToast("Download shuru...", "info");
}

// ============================================
// DELETE
// ============================================
async function deleteFile(url) {
  if (!confirm("Ye file delete karna hai?")) return;
  try {
    const res = await fetch(url, { method: "DELETE" });
    const data = await res.json();
    if (data.success) {
      showToast("File delete ho gayi", "success");
      loadSharedFiles();
      loadSentFiles();
    } else {
      showToast("Delete fail: " + (data.error || "error"), "error");
    }
  } catch (e) {
    showToast("Delete error", "error");
  }
}

// ============================================
// UPLOAD WITH PROGRESS
// ============================================
function uploadFiles(files) {
  if (!files || files.length === 0) return;

  Array.from(files).forEach(file => {
    const itemId = "p" + Date.now() + Math.random().toString(36).slice(2, 6);
    const item = document.createElement("div");
    item.className = "progress-item";
    item.id = itemId;
    item.innerHTML = `
      <div class="pi-icon">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
          <polyline points="14 2 14 8 20 8"/>
        </svg>
      </div>
      <div class="pi-info">
        <div class="pi-name">${file.name}</div>
        <div class="pi-size">${formatSize(file.size)}</div>
        <div class="progress-bar"><div class="progress-bar-fill" style="width:0%"></div></div>
      </div>
      <div class="pi-status">0%</div>
    `;
    uploadProgressList.appendChild(item);

    const xhr = new XMLHttpRequest();
    const formData = new FormData();
    formData.append("files", file);

    xhr.upload.addEventListener("progress", e => {
      if (e.lengthComputable) {
        const pct = Math.round((e.loaded / e.total) * 100);
        item.querySelector(".progress-bar-fill").style.width = pct + "%";
        item.querySelector(".pi-status").textContent = pct + "%";
      }
    });

    xhr.addEventListener("load", () => {
      if (xhr.status === 200) {
        item.querySelector(".progress-bar-fill").style.width = "100%";
        item.querySelector(".pi-status").textContent = "✓ Done";
        item.querySelector(".pi-status").className = "pi-status done";
        item.querySelector(".pi-icon").style.color = "var(--success)";
        showToast(`"${file.name}" bhej diya!`, "success");
        setTimeout(() => item.remove(), 3000);
        loadSentFiles();
      } else {
        item.querySelector(".pi-status").textContent = "✗ Fail";
        item.querySelector(".pi-status").className = "pi-status error";
        showToast(`"${file.name}" upload fail!`, "error");
      }
    });

    xhr.addEventListener("error", () => {
      item.querySelector(".pi-status").textContent = "✗ Error";
      item.querySelector(".pi-status").className = "pi-status error";
      showToast(`"${file.name}" upload error!`, "error");
    });

    // filename as query parameter - Android server ise read karta hai
    xhr.open("POST", "/api/upload?filename=" + encodeURIComponent(file.name));
    xhr.send(formData);
  });
}

// ============================================
// EVENT LISTENERS
// ============================================
uploadZone.addEventListener("click", () => fileInput.click());
fileInput.addEventListener("change", e => {
  uploadFiles(e.target.files);
  fileInput.value = "";
});

uploadZone.addEventListener("dragover", e => {
  e.preventDefault();
  uploadZone.classList.add("dragover");
});
uploadZone.addEventListener("dragleave", () => uploadZone.classList.remove("dragover"));
uploadZone.addEventListener("drop", e => {
  e.preventDefault();
  uploadZone.classList.remove("dragover");
  uploadFiles(e.dataTransfer.files);
});

// ============================================
// AUTO-REFRESH (polling - no Socket.io needed)
// ============================================
loadSharedFiles();
loadSentFiles();
setInterval(() => {
  loadSharedFiles();
  loadSentFiles();
}, 5000);

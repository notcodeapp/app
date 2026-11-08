const REPO = "notcodeapp/app";
const API = `https://api.github.com/repos/${REPO}`;
const RELEASES_PAGE = `https://github.com/${REPO}/releases`;

document.getElementById("year").textContent = new Date().getFullYear();

const stateEls = {
  loading: document.getElementById("state-loading"),
  ready: document.getElementById("state-ready"),
  empty: document.getElementById("state-empty"),
  error: document.getElementById("state-error"),
};

function show(name) {
  for (const [key, el] of Object.entries(stateEls)) {
    el.hidden = key !== name;
  }
}

function pickApk(release) {
  const apks = (release.assets || []).filter((a) =>
    a.name.toLowerCase().endsWith(".apk")
  );
  return (
    apks.find((a) => /universal/i.test(a.name)) ||
    apks.find((a) => !/debug/i.test(a.name)) ||
    apks[0] ||
    null
  );
}

function startDownload(url) {
  const a = document.createElement("a");
  a.href = url;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

function formatSize(bytes) {
  return `${(bytes / 1048576).toFixed(1)} МБ`;
}

function formatDate(iso) {
  return new Date(iso).toLocaleDateString("ru-RU", {
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

let currentDownloadUrl = null;

async function loadTargetRelease() {
  show("loading");
  const tag = new URLSearchParams(location.search).get("tag");
  const url = tag
    ? `${API}/releases/tags/${encodeURIComponent(tag)}`
    : `${API}/releases/latest`;
  let release;
  try {
    const res = await fetch(url, {
      headers: { Accept: "application/vnd.github+json" },
    });
    if (res.status === 404) {
      show("empty");
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    release = await res.json();
  } catch {
    show("error");
    return;
  }
  const apk = pickApk(release);
  if (!apk) {
    show("empty");
    return;
  }
  currentDownloadUrl = apk.browser_download_url;
  document.getElementById("ready-version").textContent =
    release.tag_name || release.name || "—";
  document.getElementById("ready-file").textContent = apk.name;
  document.getElementById("ready-size").textContent = formatSize(apk.size);
  document.getElementById("ready-release-link").href =
    release.html_url || RELEASES_PAGE;
  show("ready");
  setTimeout(() => startDownload(currentDownloadUrl), 600);
}

document.getElementById("retry-download").addEventListener("click", () => {
  if (currentDownloadUrl) startDownload(currentDownloadUrl);
});
document.getElementById("retry-inline").addEventListener("click", () => {
  if (currentDownloadUrl) startDownload(currentDownloadUrl);
});
document.getElementById("retry-fetch").addEventListener("click", loadTargetRelease);

async function loadReleaseList() {
  const listEl = document.getElementById("releases-list");
  const statusEl = document.getElementById("versions-status");
  let releases;
  try {
    const res = await fetch(`${API}/releases?per_page=10`, {
      headers: { Accept: "application/vnd.github+json" },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    releases = await res.json();
  } catch {
    statusEl.textContent = "Не удалось загрузить список. ";
    const link = document.createElement("a");
    link.href = RELEASES_PAGE;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = "Открыть релизы на GitHub ↗";
    statusEl.appendChild(link);
    return;
  }
  const published = releases.filter((r) => !r.draft);
  if (published.length === 0) {
    statusEl.textContent = "Релизов пока нет — первый уже готовится.";
    return;
  }
  statusEl.remove();
  published.forEach((release, index) => {
    const row = document.createElement("div");
    row.className = "release-row";

    const tag = document.createElement("span");
    tag.className = "release-tag";
    tag.textContent = release.tag_name || release.name || "Релиз";
    row.appendChild(tag);

    if (index === 0) {
      const badge = document.createElement("span");
      badge.className = "release-latest";
      badge.textContent = "ПОСЛЕДНИЙ";
      row.appendChild(badge);
    }

    const date = document.createElement("span");
    date.className = "release-date";
    date.textContent = release.published_at ? formatDate(release.published_at) : "";
    row.appendChild(date);

    const links = document.createElement("span");
    links.className = "release-links";

    const apk = pickApk(release);
    if (apk) {
      const dl = document.createElement("a");
      dl.className = "release-apk";
      dl.href = apk.browser_download_url;
      dl.rel = "noopener";
      dl.textContent = `Скачать APK · ${formatSize(apk.size)}`;
      links.appendChild(dl);
    }

    const notes = document.createElement("a");
    notes.className = "release-notes";
    notes.href = release.html_url || RELEASES_PAGE;
    notes.target = "_blank";
    notes.rel = "noopener noreferrer";
    notes.textContent = "Описание ↗";
    links.appendChild(notes);

    row.appendChild(links);
    listEl.appendChild(row);
  });
}

loadTargetRelease();
loadReleaseList();
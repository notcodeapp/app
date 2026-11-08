const REPO = "notcodeapp/app";

document.getElementById("year").textContent = new Date().getFullYear();

async function showLatestRelease() {
  const versionEl = document.getElementById("release-version");
  const statusEl = document.getElementById("status-apk");
  const chipEl = document.getElementById("chip-android");
  const badgeEl = document.getElementById("badge-android");
  if (!versionEl) return;
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
      headers: { Accept: "application/vnd.github+json" },
    });
    if (!res.ok) return;
    const release = await res.json();
    const apk = (release.assets || []).find((a) => a.name.toLowerCase().endsWith(".apk"));
    if (!apk) return;
    const tag = release.tag_name || release.name || "";
    versionEl.textContent = `Актуальная версия ${tag} — APK готов к скачиванию.`;
    statusEl.textContent = "доступен";
    statusEl.classList.add("status-ready");
    chipEl.textContent = `APK · ${tag}`;
    badgeEl.textContent = "ДОСТУПЕН";
    badgeEl.classList.remove("badge-soon");
    badgeEl.classList.add("badge-ready");
  } catch {
    /* сеть недоступна — остаётся состояние «скоро» */
  }
}

showLatestRelease();
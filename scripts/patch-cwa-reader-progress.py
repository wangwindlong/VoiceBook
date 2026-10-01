#!/usr/bin/env python3
"""Patch CWA's EPUB/KEPUB reader to prefer newer server progress over browser cache.

Run inside the CWA container, passing its application root. All source signatures
are checked before writing; unsupported versions are left untouched. Backups are
kept alongside the files. Recreate the container to return to the stock image.
"""
import argparse
from pathlib import Path
import shutil

MARKER = "VoiceBook CWA progress restore v1"

RESTORE = r'''
// VoiceBook CWA progress restore v1
qFinished(() => {
    if (!epub || !epub.locations) {
        voicebookRestoreComplete = true;
        return;
    }
    epub.locations.generate().then(async () => {
        const config = window.calibre;
        if (!config || !config.bookUrl || !reader || !reader.rendition) return;
        const key = "calibre.reader.progress." + config.bookUrl;
        const saved = localStorage.getItem(key);
        const localTime = Number(localStorage.getItem(key + ".updatedAt") || 0);
        const serverTime = Date.parse(config.kosyncUpdatedAt || "") || 0;
        const serverPercent = config.kosyncPercent == null ? NaN : Number(config.kosyncPercent);
        const useServer = Number.isFinite(serverPercent) && serverPercent >= 0 && serverPercent <= 100 &&
            (saved === null || localTime === 0 || serverTime > localTime);
        const percent = useServer ? serverPercent : (saved === null ? NaN : Number(saved));
        if (!Number.isFinite(percent) || percent < 0 || percent > 100) return;
        const cfi = epub.locations.cfiFromPercentage(percent / 100);
        if (!cfi) return;
        await reader.rendition.display(cfi);
        localStorage.setItem(key, String(percent));
        if (useServer) localStorage.setItem(key + ".updatedAt", String(serverTime));
        if (progressDiv) progressDiv.textContent = percent + "%";
    }).catch(error => console.warn("CWA progress restore failed", error)).finally(() => {
        // Initial rendition events must not overwrite the cached position before restoration.
        voicebookRestoreComplete = true;
    });
});
'''


def replace_once(source, before, after, filename):
    if source.count(before) != 1:
        raise ValueError(f"Unsupported CWA source in {filename}; no files changed")
    return source.replace(before, after, 1)


def patch(root):
    paths = [root / "cps/web.py", root / "cps/templates/read.html",
             root / "cps/static/js/reading/epub-progress.js"]
    sources = [p.read_text() for p in paths]
    if MARKER in sources[2]:
        if "kosyncUpdatedAt:" not in sources[1] or "kosync_progress_timestamp=kosync_progress_timestamp" not in sources[0]:
            raise ValueError("Incomplete previous patch; restore .voicebook-backup files before retrying")
        return False
    web, template, js = sources
    # Only change read_book, not the detail page's existing timestamp handling.
    start = web.index("def read_book(book_id, book_format):")
    end = web.index('@web.route(', start)
    reader = web[start:end]
    reader = replace_once(reader, "    kosync_progress = None\n", "    kosync_progress = None\n    kosync_progress_timestamp = None\n", paths[0])
    reader = replace_once(reader,
        "                kosync_progress = kobo_state.current_bookmark.progress_percent\n",
        "                kosync_progress = kobo_state.current_bookmark.progress_percent\n                kosync_progress_timestamp = kobo_state.current_bookmark.last_modified\n", paths[0])
    reader = replace_once(reader, "bookmark=bookmark, kosync_progress=kosync_progress,",
        "bookmark=bookmark, kosync_progress=kosync_progress, kosync_progress_timestamp=kosync_progress_timestamp,", paths[0])
    web = web[:start] + reader + web[end:]
    template = replace_once(template,
        "kosyncPercent: {{ kosync_progress | tojson if kosync_progress is not none else 'null' }}",
        "kosyncPercent: {{ kosync_progress | tojson if kosync_progress is not none else 'null' }},\n"
        "            kosyncUpdatedAt: {{ kosync_progress_timestamp.strftime('%Y-%m-%dT%H:%M:%SZ') | tojson if kosync_progress_timestamp is not none else 'null' }}", paths[1])
    js = replace_once(js, "window.addEventListener('locationchange',()=>{",
        "let voicebookRestoreComplete = false;\nwindow.addEventListener('locationchange',()=>{\n    if (!voicebookRestoreComplete) return;", paths[2])
    js = replace_once(js, 'localStorage.setItem("calibre.reader.progress." + bookKey, newPos);',
        'localStorage.setItem("calibre.reader.progress." + bookKey, newPos);\n        localStorage.setItem("calibre.reader.progress." + bookKey + ".updatedAt", String(Date.now()));', paths[2])
    if js.count("qFinished(()=>{") != 1 or "window.calibre.kosyncPercent" not in js:
        raise ValueError("Unsupported CWA reader restoration code; no files changed")
    js = js[:js.index("qFinished(()=>{")] + RESTORE
    # Syntax-check Python before replacing any files.
    compile(web, str(paths[0]), "exec")
    for path, content in zip(paths, [web, template, js]):
        backup = path.with_name(path.name + ".voicebook-backup")
        if backup.exists():
            raise ValueError(f"Backup already exists: {backup}; inspect it before retrying")
    for path, content in zip(paths, [web, template, js]):
        shutil.copy2(path, path.with_name(path.name + ".voicebook-backup"))
        path.write_text(content)
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="CWA application root, usually /app/calibre-web-automated")
    args = parser.parse_args()
    try:
        print("Patched; restart CWA and hard-refresh the reader page." if patch(args.root) else "Already patched.")
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")

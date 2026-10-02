#!/usr/bin/env python3
"""Patch CWA's EPUB/KEPUB reader to prefer newer server progress over browser cache.

Run inside the CWA container, passing its application root. All source signatures
are checked before writing; unsupported versions are left untouched. Backups are
kept alongside the files. Recreate the container to return to the stock image.
"""
import argparse
from pathlib import Path
import shutil

MARKER = "VoiceBook CWA progress restore v3"
PREVIOUS_MARKER = "VoiceBook CWA progress restore v2"
OLD_MARKER = "VoiceBook CWA progress restore v1"
SCRIPT_URL = "url_for('static', filename='js/reading/epub-progress.js')"
VERSIONED_SCRIPT_URL = "url_for('static', filename='js/reading/epub-progress.js', v='voicebook-progress-v3')"

SAVE = r'''let voicebookRestoreComplete = false;
let voicebookUserMoved = false;
function voicebookNavigationStarted() {
    if (!voicebookRestoreComplete) voicebookUserMoved = true;
}
reader.rendition.on("keydown", event => {
    if (["ArrowLeft", "ArrowRight", "PageUp", "PageDown", " "].includes(event.key)) voicebookNavigationStarted();
});
reader.rendition.on("touchend", voicebookNavigationStarted);
document.addEventListener("click", event => {
    if (event.target.closest && event.target.closest("#prev, #next, #tocView a, #bookmarks a")) voicebookNavigationStarted();
}, true);
function voicebookSavePosition(timestamp) {
    if (!reader || !reader.rendition || !epub || !epub.locations || !window.calibre) return;
    const location = reader.rendition.currentLocation();
    const cfi = location && location.start && location.start.cfi;
    if (!cfi) return;
    const percent = epub.locations.percentageFromCfi(cfi) * 100;
    if (!Number.isFinite(percent) || percent < 0 || percent > 100) return;
    const key = "calibre.reader.progress." + window.calibre.bookUrl;
    localStorage.setItem(key, String(percent));
    localStorage.setItem(key + ".cfi", cfi);
    localStorage.setItem(key + ".updatedAt", String(timestamp));
    // EPUB.js restores this separate settings record before the progress script runs.
    // Persist it immediately, rather than relying only on a beforeunload callback.
    if (reader.settings) reader.settings.previousLocationCfi = cfi;
    if (typeof reader.saveSettings === "function") reader.saveSettings();
    if (progressDiv) progressDiv.textContent = Math.round(percent) + "%";
}
window.addEventListener('locationchange', () => {
    if (voicebookRestoreComplete) voicebookSavePosition(Date.now());
});
'''

RESTORE = r'''
// VoiceBook CWA progress restore v3
qFinished(async () => {
    try {
        // The rendition queue can appear idle before its first asynchronous display.
        // Restoring before that display resolves lets the old cached CFI win the race.
        await reader.displayed;
        if (!epub || !epub.locations) return;
        await epub.ready;
        await epub.locations.generate();
        // A slow initial restore must not pull a reader away after they start turning pages.
        if (voicebookUserMoved) {
            voicebookSavePosition(Date.now());
            return;
        }
        const config = window.calibre;
        if (!config || !config.bookUrl || !reader || !reader.rendition) return;
        const key = "calibre.reader.progress." + config.bookUrl;
        const saved = localStorage.getItem(key);
        const savedCfi = localStorage.getItem(key + ".cfi") ||
            (reader.settings && reader.settings.previousLocationCfi);
        const nativePercent = savedCfi ? epub.locations.percentageFromCfi(savedCfi) * 100 : NaN;
        const localPercent = Number.isFinite(nativePercent) ? nativePercent : (saved === null ? NaN : Number(saved));
        const localTime = Number(localStorage.getItem(key + ".updatedAt") || 0);
        const serverTime = Date.parse(config.kosyncUpdatedAt || "") || 0;
        const serverPercent = config.kosyncPercent == null ? NaN : Number(config.kosyncPercent);
        const useServer = Number.isFinite(serverPercent) && serverPercent >= 0 && serverPercent <= 100 &&
            ((!savedCfi && saved === null) || (!savedCfi && localTime === 0) ||
                (localTime > 0 && serverTime > localTime) ||
                (savedCfi && localTime === 0 && serverPercent > localPercent + 1) ||
                // v1 could stamp an old position during delayed initial relocation.
                // On migration, keep the farther valid server position in that case.
                (!savedCfi && serverPercent > Number(saved) + 1));
        const percent = useServer ? serverPercent : localPercent;
        if (!Number.isFinite(percent) || percent < 0 || percent > 100) return;
        const cfi = !useServer && savedCfi ? savedCfi : epub.locations.cfiFromPercentage(percent / 100);
        if (!cfi) return;
        // display() resolves before EPUB.js reports the relocated page. Wait for both
        // so delayed restoration events cannot be mistaken for a new local edit.
        let onRelocated;
        let timer;
        const relocated = new Promise(resolve => {
            onRelocated = () => resolve();
            reader.rendition.on("relocated", onRelocated);
            timer = setTimeout(resolve, 3000);
        });
        try {
            await reader.rendition.display(cfi);
            await relocated;
        } finally {
            reader.rendition.off("relocated", onRelocated);
            clearTimeout(timer);
        }
        voicebookSavePosition(useServer ? serverTime : localTime);
    } catch (error) {
        console.warn("CWA progress restore failed", error);
    } finally {
        voicebookRestoreComplete = true;
    }
});
'''


def replace_once(source, before, after, filename):
    if source.count(before) != 1:
        raise ValueError(f"Unsupported CWA source in {filename}; no files changed")
    return source.replace(before, after, 1)


def patch(root):
    paths = [root / "cps/web.py", root / "cps/templates/read.html",
             root / "cps/static/js/reading/epub-progress.js",
             root / "cps/static/js/reading/epub.js"]
    sources = [p.read_text() for p in paths]
    if any(marker in sources[2] for marker in (MARKER, PREVIOUS_MARKER, OLD_MARKER)):
        if "kosyncUpdatedAt:" not in sources[1] or "kosync_progress_timestamp=kosync_progress_timestamp" not in sources[0]:
            raise ValueError("Incomplete previous patch; restore .voicebook-backup files before retrying")
        if MARKER in sources[2]:
            if VERSIONED_SCRIPT_URL not in sources[1] or "previousLocationCfi: localStorage.getItem" not in sources[3]:
                raise ValueError("Incomplete v3 patch; inspect backups before retrying")
            return False
    web, template, js, entry = sources
    upgrading = OLD_MARKER in js or PREVIOUS_MARKER in js
    if upgrading:
        backup = paths[2].with_name(paths[2].name + ".voicebook-backup")
        if not backup.exists():
            raise ValueError("Previous patch has no original JS backup; no files changed")
        js = backup.read_text()
    if not upgrading:
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
            "            kosyncUpdatedAt: {{ kosync_progress_timestamp.strftime('%Y-%m-%dT%H:%M:%S.%fZ') | tojson if kosync_progress_timestamp is not none else 'null' }}", paths[1])
    event_start = js.index("window.addEventListener('locationchange',()=>{")
    event_end = js.index("var epub=ePub(calibre.bookUrl)", event_start)
    if js[event_start:event_end].count("localStorage.setItem") != 1:
        raise ValueError("Unsupported CWA position persistence code; no files changed")
    js = js[:event_start] + SAVE + "\n" + js[event_end:]
    js = replace_once(js, "var epub=ePub(calibre.bookUrl)", "var epub=reader.book", paths[2])
    if js.count("qFinished(()=>{") != 1 or "window.calibre.kosyncPercent" not in js:
        raise ValueError("Unsupported CWA reader restoration code; no files changed")
    js = js[:js.index("qFinished(()=>{")] + RESTORE
    if upgrading and "strftime('%Y-%m-%dT%H:%M:%SZ')" in template:
        template = replace_once(template, "strftime('%Y-%m-%dT%H:%M:%SZ')", "strftime('%Y-%m-%dT%H:%M:%S.%fZ')", paths[1])
    if "v='voicebook-progress-v2'" in template:
        template = replace_once(template, "v='voicebook-progress-v2'", "v='voicebook-progress-v3'", paths[1])
    else:
        template = replace_once(template, SCRIPT_URL, VERSIONED_SCRIPT_URL, paths[1])
    template = replace_once(template, "url_for('static', filename='js/reading/epub.js')",
        "url_for('static', filename='js/reading/epub.js', v='voicebook-progress-v3')", paths[1])
    # Supply the exact local anchor to the very first display, before async locations generation.
    entry = replace_once(entry, '        restore: true,',
        '        restore: true,\n        previousLocationCfi: localStorage.getItem("calibre.reader.progress." + calibre.bookUrl + ".cfi") || undefined,', paths[3])
    js = replace_once(js, 'let progressDiv=document.getElementById("progress");',
        'let progressDiv=document.getElementById("progress");\n'
        'if (progressDiv) {\n'
        '    const savedPercent = localStorage.getItem("calibre.reader.progress." + calibre.bookUrl);\n'
        '    const percent = savedPercent === null ? NaN : Number(savedPercent);\n'
        '    progressDiv.textContent = Number.isFinite(percent) && percent >= 0 && percent <= 100 ? Math.round(percent) + "%" : "…";\n'
        '}', paths[2])
    # Syntax-check Python before replacing any files.
    compile(web, str(paths[0]), "exec")
    for path, content in zip(paths, [web, template, js, entry]):
        backup = path.with_name(path.name + ".voicebook-backup")
        if not upgrading and backup.exists():
            raise ValueError(f"Backup already exists: {backup}; inspect it before retrying")
    for path, content in zip(paths, [web, template, js, entry]):
        backup = path.with_name(path.name + (".voicebook-v2-backup" if PREVIOUS_MARKER in sources[2] else ".voicebook-v1-backup" if upgrading else ".voicebook-backup"))
        if not backup.exists():
            shutil.copy2(path, backup)
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

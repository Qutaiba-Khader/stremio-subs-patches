'use strict';
// A stand-in for the SubSense addon, for testing the "Subtitle auto-refresh" patch with no real data: one test movie
// (a generated video with its clock burned in), and three Arabic entries in the addon's own shape. Entry 1 is ready at
// once; entries 2 and 3 serve the addon's exact "processing" file (same text, same X-Addon-Processing header) until
// READY2_S / READY3_S seconds after the subtitle list was asked for, then a file whose every line names its own start
// time, so a screenshot shows whether the drawn line matches the video clock.
//   node test-addon.js            (VIDEO=path/to/patchtest.mp4 PORT=7790 BASE=http://127.0.0.1:7790)
// GET /sub/<job>/<k>.state answers like the addon's light "ready?" route; NO_STATE=1 makes it a bare 404 and
// NO_STATE=json a JSON 404 (both: an older addon without the route).
// ONE=1: the addon's "one entry that improves over time" — the list offers one entry; its versions are ready
// VERSIONS="0,40,90" seconds after the list call (any number of versions); BROKEN_AFTER=s: past that time every version
// not yet ready is dropped (a promised version that never comes); SILENT_AFTER=s: past that time the versions question
// never answers (an addon gone silent; the app's promise expires after NEXTBY_S, default 1800). Each version's lines say
// which version they are.
const http = require('node:http');
const fs = require('node:fs');

const PORT = Number(process.env.PORT || 7790);
const BASE = process.env.BASE || `http://127.0.0.1:${PORT}`;
const VIDEO = process.env.VIDEO || `${__dirname}/patchtest.mp4`;
const READY = { 1: 0, 2: Number(process.env.READY2_S || 45), 3: Number(process.env.READY3_S || 90) };
const ENABLED = process.env.ENABLED !== '0';
const ONE = process.env.ONE === '1';
const VERSIONS = String(process.env.VERSIONS || '0,40,90').split(',').map(Number);
const BROKEN_AFTER = process.env.BROKEN_AFTER ? Number(process.env.BROKEN_AFTER) : null;
const SILENT_AFTER = process.env.SILENT_AFTER ? Number(process.env.SILENT_AFTER) : null;
const NEXTBY_MS = Number(process.env.NEXTBY_S || 1800) * 1000;
const jobs = new Map(); // jobId → created (ms)
const served = new Map(); // ONE=1: jobId → the version the one entry's fetch served (0 = processing file), like the addon (D77)

const log = (s) => console.log(`${new Date().toISOString().slice(11, 23)} ${s}`);
const pad = (n, w = 2) => String(n).padStart(w, '0');
const srtTime = (ms) => `${pad(Math.floor(ms / 3600000))}:${pad(Math.floor(ms / 60000) % 60)}:${pad(Math.floor(ms / 1000) % 60)},${pad(ms % 1000, 3)}`;
const DIGIT = ['', '١', '٢', '٣'];

// the invisible tag the addon puts in its processing file: "ssx1 <path>" in Unicode tag characters (NO_TAG=1: none)
const tagFor = (path) => (process.env.NO_TAG === '1' ? '' : Array.from(`ssx1 ${path}`, (c) => String.fromCodePoint(0xE0000 + c.charCodeAt(0))).join(''));

function placeholder(job, k) { // results.js placeholderText/placeholderCues, verbatim (since D77 no visible text; OLD_TEXT=1: the older wording)
    const shown = process.env.OLD_TEXT === '1' ? `ai subtitle ${k} processing — pick it again in ~2 min\nالترجمة ${DIGIT[k] || k} قيد المعالجة — اخترها مرة أخرى بعد دقيقتين تقريباً` : '';
    const text = `${shown}${tagFor(`/sub/${job}/${k}.state`)}`;
    const out = [];
    for (let t = 0, i = 1; t < 4 * 3600 * 1000; t += 60000, i++) out.push(`${i}\n${srtTime(t)} --> ${srtTime(t + 59500)}\n${text}\n`);
    return out.join('\n');
}

function finished(k, word = 'الإدخال') { // a line every 2 s, 1.5 s long, naming its entry and its own start time
    const out = [];
    for (let t = 0, i = 1; t < 600000; t += 2000, i++) {
        out.push(`${i}\n${srtTime(t)} --> ${srtTime(t + 1500)}\n${word} ${DIGIT[k] || k} — سطر ${i}\n${srtTime(t).replace(',', '.')}\n`);
    }
    return out.join('\n');
}

// ONE=1: version v (1-based) of the one entry
const age = (job) => (Date.now() - jobs.get(job)) / 1000;
const versionReady = (job, v) => age(job) >= VERSIONS[v - 1];
const versionDropped = (job, v) => BROKEN_AFTER != null && age(job) >= BROKEN_AFTER && !versionReady(job, v);
function versionsOf(job, have) {
    let best = 0;
    for (let v = have + 1; v <= VERSIONS.length; v++) if (versionReady(job, v)) best = v;
    const top = best || have;
    let more = false;
    for (let v = top + 1; v <= VERSIONS.length; v++) if (!versionReady(job, v) && !versionDropped(job, v)) more = true;
    const plan = { entries: 1, versions: VERSIONS.map((_, i) => ({ v: i + 1, state: versionReady(job, i + 1) ? 'ready' : versionDropped(job, i + 1) ? 'dropped' : 'pending' })) };
    return { best, more, plan };
}

const isReady = (job, k) => Date.now() - jobs.get(job) >= READY[k] * 1000;
const json = (res, obj, code = 200) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }); res.end(JSON.stringify(obj)); };

const manifest = {
    id: 'org.subsense.patchtest', version: '0.1.0', name: 'SubSense patch test',
    description: 'Test stand-in for the SubSense addon (no real data).',
    resources: ['catalog', 'meta', 'stream', 'subtitles'], types: ['movie'], idPrefixes: ['pt'],
    catalogs: [{ type: 'movie', id: 'patchtest', name: 'Patch test' }]
};
const meta = { id: 'pt1', type: 'movie', name: 'Subtitle swap test', description: 'Generated test video (10 min).', runtime: '10 min' };

http.createServer((req, res) => {
    const url = new URL(req.url, BASE);
    const p = decodeURIComponent(url.pathname);
    log(`${req.method} ${req.url} · ${req.headers['user-agent'] || '-'}${req.headers.range ? ` · ${req.headers.range}` : ''}`);
    let m;
    if (p === '/manifest.json') return json(res, manifest);
    if (/^\/catalog\/movie\/patchtest/.test(p)) return json(res, { metas: [meta] });
    if (/^\/meta\/movie\/pt1/.test(p)) return json(res, { meta });
    if (/^\/stream\/movie\/pt1/.test(p)) return json(res, { streams: [{ url: `${BASE}/video.mp4`, name: 'Test', title: 'Generated test video 480p' }] });
    if (/^\/subtitles\/movie\/pt1/.test(p)) {
        const job = `t${Date.now().toString(36)}`;
        jobs.set(job, Date.now());
        log(`new test job ${job}: entry 2 ready in ${READY[2]} s, entry 3 in ${READY[3]} s`);
        if (ONE) {
            log(`  one entry, versions at ${VERSIONS.join(', ')} s${BROKEN_AFTER != null ? `, dropped after ${BROKEN_AFTER} s` : ''}`);
            return json(res, { subtitles: [{ id: `${job}:1`, url: `${BASE}/sub/${job}/1.srt?u=1`, lang: 'ara' }] });
        }
        return json(res, { subtitles: [1, 2, 3].map((k) => ({ id: `${job}:${k}`, url: `${BASE}/sub/${job}/${k}.srt`, lang: 'ara' })) });
    }
    if (ONE && (m = p.match(/^\/sub\/([^/]+)\/(\d+)\.(srt|state)$/)) && jobs.has(m[1])) {
        const [, job, kk, kind] = m;
        const k = Number(kk);
        res.setHeader('Cache-Control', 'no-store');
        if (kind === 'state') {
            const caps = String(url.searchParams.get('caps') || '').split(',');
            const asked = Number(url.searchParams.get('have')) || 0;
            // like the addon (D77): Stremio's own copy is the version its fetch was served — read again on every look,
            // as the question may come before Stremio's own fetch is answered
            const ownCopy = !asked && url.searchParams.get('showing') === 'own';
            const haveNow = () => (ownCopy ? (served.has(job) ? served.get(job) : null) : asked);
            let have = haveNow() || 0;
            const until = Date.now() + Math.min(25, Number(url.searchParams.get('wait')) || 0) * 1000;
            log(`  state v? have=${have} showing=${url.searchParams.get('showing')} pos=${url.searchParams.get('pos')} caps=${caps.join('+')}`);
            if (k !== 1) return json(res, { subsense: 1, v: 1, op: 'stop', error: 'unknown job or slot' }, 404);
            if (!caps.includes('versions')) { // like the real addon: an older app gets the protocol-1 answer (best so far)
                const r1 = versionReady(job, 1);
                return json(res, { subsense: 1, v: 1, op: r1 ? 'replace' : 'wait', ready: r1, enabled: true, ...(r1 ? { file: `/sub/${job}/1.srt?u=1` } : {}), retryMs: 1000 });
            }
            if (SILENT_AFTER != null && age(job) >= SILENT_AFTER) { log('  (silent: no answer)'); return undefined; }
            const answer = () => {
                const had = haveNow();
                have = had || 0;
                const vs = versionsOf(job, have);
                const fetchPending = ownCopy && had === null && vs.best;
                if (((!vs.best && vs.more) || fetchPending) && Date.now() < until && !res.destroyed) return setTimeout(answer, 500);
                const base = { subsense: 1, v: 1, plan: vs.plan, retryMs: 1000 };
                if (vs.best) {
                    log(`  → version ${vs.best}${vs.more ? ' (more may follow)' : ' (the last)'}`);
                    return json(res, { ...base, op: 'replace', file: `/sub/${job}/${vs.best}.srt`, version: vs.best, more: vs.more, ...(vs.more ? { nextBy: NEXTBY_MS } : {}),
                        toast: '', swap: { gapMs: 400, maxWaitMs: 8000 } });
                }
                log(`  → ${vs.more ? 'wait' : 'stop (nothing more)'}`);
                return json(res, vs.more ? { ...base, op: 'wait', version: have, more: true, nextBy: NEXTBY_MS } : { ...base, op: 'stop', version: have, more: false });
            };
            return answer();
        }
        // the one entry (k 1, ?u=1) serves the latest ready version when fetched; k ≥ 2 = that version's own file
        let v = k;
        if (k === 1) for (let i = 1; i <= VERSIONS.length; i++) if (versionReady(job, i)) v = i;
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Content-Type', 'application/x-subrip; charset=utf-8');
        if (!versionReady(job, v)) { if (k === 1) served.set(job, 0); res.setHeader('X-Addon-Processing', '1'); log(`  → version ${v}: processing file`); return res.end(placeholder(job, k)); }
        if (k === 1) served.set(job, v);
        log(`  → version ${v} file`);
        return res.end(finished(v, 'النسخة'));
    }
    if ((m = p.match(/^\/sub\/([^/]+)\/(\d)\.(srt|state)$/)) && jobs.has(m[1]) && READY[m[2]] != null) {
        const [, job, k, kind] = m;
        const ready = isReady(job, k);
        if (kind === 'state') {
            if (process.env.NO_STATE === '1') { res.writeHead(404); return res.end(); }
            // an addon from before the question route: its catch-all answers every unknown route with a JSON 404
            if (process.env.NO_STATE === 'json') { res.writeHead(404, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ error: 'Not found', path: p })); }
            res.setHeader('Cache-Control', 'no-store');
            // like the addon: held open up to wait= s (≤ 25) until the file is ready
            const until = Date.now() + Math.min(25, Number(url.searchParams.get('wait')) || 0) * 1000;
            const answer = () => {
                const r = isReady(job, k);
                if (!r && ENABLED && Date.now() < until && !res.destroyed) return setTimeout(answer, 500);
                log(`  → state ${k}: ${r ? 'ready' : 'not ready'}`);
                return json(res, { subsense: 1, v: 1, op: !ENABLED ? 'stop' : r ? 'replace' : 'wait', ready: r, enabled: ENABLED, ...(r ? { file: `/sub/${job}/${k}.srt` } : {}), retryMs: 1000 });
            };
            return answer();
        }
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Content-Type', 'application/x-subrip; charset=utf-8');
        res.setHeader('Cache-Control', ready ? 'max-age=3600' : 'no-store');
        if (!ready) res.setHeader('X-Addon-Processing', '1');
        log(`  → entry ${k}: ${ready ? 'FINISHED file' : 'processing file'}`);
        return res.end(ready ? finished(k) : placeholder(job, k));
    }
    if (p === '/patch/v1/subtitle') {
        const src = String(url.searchParams.get('src') || '');
        const caps = String(url.searchParams.get('caps') || '').split(',');
        const mm = /\/sub\/([^/?#]+)\/1\.srt\?u=1$/.exec(src);
        if (ONE && mm && caps.includes('follow') && caps.includes('versions')) {
            // like the addon (D77): already the best and nothing more to come → nothing new, at once
            // (also before Stremio's own fetch: nothing more is coming, so that fetch gets the final version)
            if (jobs.has(mm[1])) {
                const vs = versionsOf(mm[1], 0);
                const had = served.has(mm[1]) ? served.get(mm[1]) : null;
                if (!vs.more && (had === null || had >= vs.best)) { log('  generic → stop (nothing new)'); return json(res, { subsense: 1, v: 1, op: 'stop', version: had === null ? vs.best : had, more: false }); }
            }
            log('  generic → follow');
            return json(res, { subsense: 1, v: 1, op: 'wait', ask: `/sub/${mm[1]}/1.state`, retryMs: 0 });
        }
        return json(res, { v: 1, op: 'stop' });
    }
    if (p === '/patch/v1/report' && req.method === 'POST') {
        let body = '';
        req.on('data', (d) => { body += d; });
        return req.on('end', () => { log(`  REPORT ${body}`); json(res, { ok: true }); });
    }
    if (p === '/video.mp4') {
        const size = fs.statSync(VIDEO).size;
        const r = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
        if (r) {
            const a = r[1] ? Number(r[1]) : size - Number(r[2]);
            const b = r[1] && r[2] ? Math.min(Number(r[2]), size - 1) : size - 1;
            if (!(a >= 0 && a <= b)) { // past the end (a player probing the size): not satisfiable, never a crash
                res.writeHead(416, { 'Content-Range': `bytes */${size}` });
                return res.end();
            }
            res.writeHead(206, { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${a}-${b}/${size}`, 'Content-Length': b - a + 1 });
            return fs.createReadStream(VIDEO, { start: a, end: b }).pipe(res);
        }
        res.writeHead(200, { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes', 'Content-Length': size });
        return fs.createReadStream(VIDEO).pipe(res);
    }
    // like the real addon: an unknown job's question gets the protocol's own stop
    if (/^\/sub\/[^/]+\/\d+\.state$/.test(p)) return json(res, { subsense: 1, v: 1, op: 'stop', error: 'unknown job or slot' }, 404);
    res.writeHead(404);
    res.end();
}).listen(PORT, '127.0.0.1', () => log(`test addon on ${BASE}/manifest.json`));

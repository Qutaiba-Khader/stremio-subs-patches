'use strict';
// A stand-in for the SubSense addon, for testing the "Subtitle auto-refresh" patch with no real data: one test movie
// (a generated video with its clock burned in), and three Arabic entries in the addon's own shape. Entry 1 is ready at
// once; entries 2 and 3 serve the addon's exact "processing" file (same text, same X-Addon-Processing header) until
// READY2_S / READY3_S seconds after the subtitle list was asked for, then a file whose every line names its own start
// time, so a screenshot shows whether the drawn line matches the video clock.
//   node test-addon.js            (VIDEO=path/to/patchtest.mp4 PORT=7790 BASE=http://127.0.0.1:7790)
// GET /sub/<job>/<k>.state answers like the addon's light "ready?" route; NO_STATE=1 makes it a bare 404 and
// NO_STATE=json a JSON 404 (both: an older addon without the route).
const http = require('node:http');
const fs = require('node:fs');

const PORT = Number(process.env.PORT || 7790);
const BASE = process.env.BASE || `http://127.0.0.1:${PORT}`;
const VIDEO = process.env.VIDEO || `${__dirname}/patchtest.mp4`;
const READY = { 1: 0, 2: Number(process.env.READY2_S || 45), 3: Number(process.env.READY3_S || 90) };
const ENABLED = process.env.ENABLED !== '0';
const jobs = new Map(); // jobId → created (ms)

const log = (s) => console.log(`${new Date().toISOString().slice(11, 23)} ${s}`);
const pad = (n, w = 2) => String(n).padStart(w, '0');
const srtTime = (ms) => `${pad(Math.floor(ms / 3600000))}:${pad(Math.floor(ms / 60000) % 60)}:${pad(Math.floor(ms / 1000) % 60)},${pad(ms % 1000, 3)}`;
const DIGIT = ['', '١', '٢', '٣'];

// the invisible tag the addon puts in its processing file: "ssx1 <path>" in Unicode tag characters (NO_TAG=1: none)
const tagFor = (path) => (process.env.NO_TAG === '1' ? '' : Array.from(`ssx1 ${path}`, (c) => String.fromCodePoint(0xE0000 + c.charCodeAt(0))).join(''));

function placeholder(job, k) { // results.js placeholderText/placeholderCues, verbatim
    const text = `ai subtitle ${k} processing — pick it again in ~2 min\nالترجمة ${DIGIT[k] || k} قيد المعالجة — اخترها مرة أخرى بعد دقيقتين تقريباً${tagFor(`/sub/${job}/${k}.state`)}`;
    const out = [];
    for (let t = 0, i = 1; t < 4 * 3600 * 1000; t += 60000, i++) out.push(`${i}\n${srtTime(t)} --> ${srtTime(t + 59500)}\n${text}\n`);
    return out.join('\n');
}

function finished(k) { // a line every 2 s, 1.5 s long, naming its entry and its own start time
    const out = [];
    for (let t = 0, i = 1; t < 600000; t += 2000, i++) {
        out.push(`${i}\n${srtTime(t)} --> ${srtTime(t + 1500)}\nالإدخال ${DIGIT[k]} — سطر ${i}\n${srtTime(t).replace(',', '.')}\n`);
    }
    return out.join('\n');
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
        return json(res, { subtitles: [1, 2, 3].map((k) => ({ id: `${job}:${k}`, url: `${BASE}/sub/${job}/${k}.srt`, lang: 'ara' })) });
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
    if (p === '/patch/v1/subtitle') return json(res, { v: 1, op: 'stop' });
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
            res.writeHead(206, { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${a}-${b}/${size}`, 'Content-Length': b - a + 1 });
            return fs.createReadStream(VIDEO, { start: a, end: b }).pipe(res);
        }
        res.writeHead(200, { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes', 'Content-Length': size });
        return fs.createReadStream(VIDEO).pipe(res);
    }
    res.writeHead(404);
    res.end();
}).listen(PORT, '127.0.0.1', () => log(`test addon on ${BASE}/manifest.json`));

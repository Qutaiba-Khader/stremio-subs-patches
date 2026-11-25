#!/usr/bin/env python3
"""Patch the current Stremio apps with this bundle exactly as morphe-builder does, and fail unless every patch applied.

  ./gradlew :patches:build && python3 tools/stremio_check.py [--work DIR]

The apps, their patches and their options are in tools/stremio-check.json (copied from morphe-builder's config.toml).
Each app is downloaded from Stremio's own feed (its current version), the patching is done with the newest Morphe CLI
(morphe-desktop) and the newest release of every other bundle named there, and the result must show:
  - the CLI ended without an error and wrote the APK
  - "Applied: <name>" for every patch named in the file
  - no warning that THIS bundle was built for an older Morphe Patcher than the CLI's
A throwaway keystore signs the test APKs; they are not published. GITHUB_TOKEN is used for the API when set.
"""
import argparse
import json
import os
import re
import secrets
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FEED = "https://api.strem.io/downloads/config"
FLAVOURS = {"android_tv": "androidTV", "android": "android"}
ARCH_KEYS = {"arm64-v8a": "arm64", "armeabi-v7a": "arm", "x86_64": "x86_64", "x86": "x86"}
OLD_PATCHER = re.compile(r"^\s+(.+?) \S+, built for \S+ Patcher (\S+)", re.M)


def _req(url, accept=None):
    req = urllib.request.Request(url, headers={"User-Agent": "stremio-patches-check"})
    if accept:
        req.add_header("Accept", accept)
    if url.startswith("https://api.github.com/") and os.getenv("GITHUB_TOKEN"):
        req.add_header("Authorization", f"Bearer {os.environ['GITHUB_TOKEN']}")
    return req


def get_json(url):
    with urllib.request.urlopen(_req(url), timeout=60) as resp:
        return json.loads(resp.read())


def download(url, dest, accept=None):
    if dest.exists() and dest.stat().st_size:
        return dest
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(_req(url, accept), timeout=600) as resp, open(tmp, "wb") as out:
        while chunk := resp.read(1 << 20):
            out.write(chunk)
    tmp.rename(dest)
    return dest


def release_asset(repo, suffix, work):
    """-> (tag, local file) of the newest release asset of `repo` whose name ends with `suffix`."""
    rel = get_json(f"https://api.github.com/repos/{repo}/releases/latest")
    for a in rel["assets"]:
        if a["name"].endswith(suffix):
            return rel["tag_name"], download(a["url"], work / a["name"], accept="application/octet-stream")
    sys.exit(f"{repo} {rel['tag_name']} has no *{suffix} asset")


def stremio_apk(feed_id, arch, work, feed):
    for e in feed["entries"]:
        if e.get("id") == feed_id:
            v = e["value"]
            name, code = v["versionName"], v["versionCode"][ARCH_KEYS[arch]]
            tag = v.get("tag") or f"v{name}"
            url = f"https://dl.strem.io/android/{tag}-{FLAVOURS[feed_id]}/com.stremio.one-{name}-{code}-{arch}.apk"
            return name, download(url, work / f"stremio-{feed_id}-{name}-{arch}.apk")
    sys.exit(f"Stremio's feed has no entry '{feed_id}'")


def own_bundle():
    ver = re.search(r"^version\s*=\s*(\S+)", (ROOT / "gradle.properties").read_text(), re.M).group(1)
    path = ROOT / "patches" / "build" / "libs" / f"patches-{ver}.mpp"
    if not path.exists():
        sys.exit(f"{path} is missing: run ./gradlew :patches:build first")
    return ver, path


def check_output(out, rc, apk, wanted, own_name):
    """-> the list of what is wrong with one patch run (empty = good)."""
    bad = []
    if rc != 0:
        bad.append(f"the CLI ended with exit code {rc}")
    if not apk.exists() or apk.stat().st_size == 0:
        bad.append("no patched APK was written")
    applied = set(re.findall(r"Applied: (.+?)\s*$", out, re.M))
    bad += [f"not applied: {p}" for p in wanted if p not in applied]
    bad += [f"failed: {line.strip()}" for line in out.splitlines() if re.search(r"\bSEVERE\b|\bfailed\b", line, re.I)]
    bad += [f"this bundle is built for an older Morphe Patcher ({m.group(2)}) than the CLI's"
            for m in OLD_PATCHER.finditer(out) if m.group(1) == own_name]
    return bad


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--work", default=str(ROOT / "build" / "stremio-check"))
    args = ap.parse_args(argv)
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    conf = json.loads((ROOT / "tools" / "stremio-check.json").read_text())
    ver, own = own_bundle()
    cli_tag, cli = release_asset("MorpheApp/morphe-desktop", "-all.jar", work)
    print(f"this bundle: {conf['bundle_name']} {ver}; Morphe CLI {cli_tag}")
    bundles = {"self": own}
    feed = get_json(FEED)
    keystore = work / "check.keystore"
    keystore.unlink(missing_ok=True)
    password = secrets.token_hex(12)
    subprocess.run(["keytool", "-genkeypair", "-keystore", str(keystore), "-storetype", "PKCS12", "-alias", "check",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-dname", "CN=stremio-check",
                    "-storepass", password, "-keypass", password], check=True, capture_output=True)
    failures = 0
    for app in conf["apps"]:
        name, apk = stremio_apk(app["feed"], app["arch"], work, feed)
        cmd = ["java", "-jar", str(cli), "patch", str(apk.resolve()), "-o", str(work / f"{app['name']}.apk"),
               f"--keystore={keystore}", f"--keystore-password={password}", f"--keystore-entry-password={password}",
               "--keystore-entry-alias=check", "-f"]
        wanted = []
        for src in app["sources"]:
            if src["bundle"] not in bundles:
                tag, path = release_asset(src["bundle"].removeprefix("github:"), ".mpp", work)
                print(f"  {src['bundle']} {tag}")
                bundles[src["bundle"]] = path
            cmd += ["--patches", str(bundles[src["bundle"]].resolve())]
            for p in src["include"]:
                cmd += ["-e", p]
            wanted += src["include"]
        cmd += ["--exclusive", *app.get("args", [])]
        for p in app.get("after", []):
            cmd += ["-e", p]
        wanted += app.get("after", [])
        cmd += ["--striplibs", app["arch"]]
        out_apk = work / f"{app['name']}.apk"
        out_apk.unlink(missing_ok=True)
        print(f"\n== {app['name']}: Stremio {name} ({app['arch']})", flush=True)
        res = subprocess.run(cmd, capture_output=True, text=True)
        out = res.stdout + res.stderr
        (work / f"{app['name']}.log").write_text(out)
        print(out.rstrip(), flush=True)
        bad = check_output(out, res.returncode, out_apk, wanted, conf["bundle_name"])
        if bad:
            failures += 1
            print(f"FAIL {app['name']} (Stremio {name}):\n  " + "\n  ".join(bad), flush=True)
        else:
            print(f"OK   {app['name']} (Stremio {name}): {len(wanted)} patches applied", flush=True)
        summary = os.getenv("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as s:
                s.write(f"- {'❌' if bad else '✅'} **{app['name']}** on Stremio {name}: "
                        + ("; ".join(bad) if bad else f"{len(wanted)} patches applied") + "\n")
    keystore.unlink(missing_ok=True)
    print(f"\n{len(conf['apps']) - failures} of {len(conf['apps'])} apps patched cleanly")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""The Morphe versions this bundle is built on, and the newest ones.

  python3 tools/morphe_versions.py                 print what the last release was built on and the newest versions
  python3 tools/morphe_versions.py --apply         also write the newest versions into the build files
  --github-output FILE                             append changed=, released=, target= for GitHub Actions

The newest versions:
  morphe-patcher  what the newest morphe-desktop release is built with: that CLI applies this bundle in morphe-builder,
                  and it warns about a bundle built for an older patcher than its own patches
  smali           what that morphe-patcher release is built with (they must match)
  plugin          the newest stable tag of the app.morphe.patches Gradle plugin

changed=true means the last release (the newest v* tag) was built on other versions, so a new release is due. It is
read from the tag, not from the working tree, so a hand-made upgrade merged into main is released too.
GITHUB_TOKEN is used when set (the API allows 60 calls an hour without it).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOML = "gradle/libs.versions.toml"
SETTINGS = "settings.gradle.kts"
API = "https://api.github.com/repos/MorpheApp"

PATCHER_RE = re.compile(r'^(morphe-patcher\s*=\s*")([^"]+)(")', re.M)
SMALI_RE = re.compile(r'^(smali\s*=\s*")([^"]+)(")', re.M)
PLUGIN_RE = re.compile(r'(id\("app\.morphe\.patches"\)\s+version\s+")([^"]+)(")')
STABLE_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


def _get(url, raw=False):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github.raw" if raw else "application/vnd.github+json",
                                               "User-Agent": "stremio-patches-morphe-versions"})
    token = os.getenv("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=60) as resp:
        body = resp.read().decode()
    return body if raw else json.loads(body)


def _one(regex, text, what):
    m = regex.search(text)
    if not m:
        sys.exit(f"cannot find the {what} version")
    return m.group(2)


def read(toml_text, settings_text):
    return {"patcher": _one(PATCHER_RE, toml_text, "morphe-patcher"),
            "smali": _one(SMALI_RE, toml_text, "smali"),
            "plugin": _one(PLUGIN_RE, settings_text, "app.morphe.patches plugin")}


def newest_stable(tags):
    best = None
    for t in tags:
        m = STABLE_TAG.match(t)
        if m and (best is None or tuple(map(int, m.groups())) > best[0]):
            best = (tuple(map(int, m.groups())), t)
    if best is None:
        sys.exit("morphe-patches-gradle-plugin has no stable tag")
    return best[1].lstrip("v")


def newest():
    cli_tag = _get(f"{API}/morphe-desktop/releases/latest")["tag_name"]
    patcher = _one(PATCHER_RE, _get(f"{API}/morphe-desktop/contents/{TOML}?ref={cli_tag}", raw=True),
                   f"morphe-patcher (morphe-desktop {cli_tag})")
    smali = _one(SMALI_RE, _get(f"{API}/morphe-patcher/contents/{TOML}?ref=v{patcher}", raw=True),
                 f"smali (morphe-patcher v{patcher})")
    plugin = newest_stable(t["name"] for t in _get(f"{API}/morphe-patches-gradle-plugin/tags?per_page=100"))
    return {"patcher": patcher, "smali": smali, "plugin": plugin, "cli": cli_tag.lstrip("v")}


def write(target, root=ROOT):
    for name, pairs in ((TOML, ((PATCHER_RE, "patcher"), (SMALI_RE, "smali"))), (SETTINGS, ((PLUGIN_RE, "plugin"),))):
        path = root / name
        text = path.read_text()
        for regex, key in pairs:
            text = regex.sub(lambda m, v=target[key]: m.group(1) + v + m.group(3), text, count=1)
        path.write_text(text)


def released(root=ROOT):
    """-> (tag, versions) of the newest v* tag, or (None, None) when there is no release yet."""
    run = lambda *a: subprocess.run(["git", *a], cwd=root, capture_output=True, text=True)  # noqa: E731
    tag = run("describe", "--tags", "--abbrev=0", "--match", "v*").stdout.strip()
    if not tag:
        return None, None
    return tag, read(run("show", f"{tag}:{TOML}").stdout, run("show", f"{tag}:{SETTINGS}").stdout)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--github-output")
    args = ap.parse_args(argv)
    tree = read((ROOT / TOML).read_text(), (ROOT / SETTINGS).read_text())
    tag, rel = released()
    target = newest()
    keys = ("patcher", "smali", "plugin")
    changed = rel is None or any(rel[k] != target[k] for k in keys)
    show = lambda v: f"patcher {v['patcher']}, smali {v['smali']}, plugin {v['plugin']}" if v else "none"  # noqa: E731
    print(f"last release {tag or '-'}: {show(rel)}")
    print(f"working tree: {show(tree)}")
    print(f"newest (morphe-desktop {target['cli']}): {show(target)}")
    print("a new release is due" if changed else "the last release is on the newest versions")
    if args.apply and any(tree[k] != target[k] for k in keys):
        write(target)
        print("wrote the newest versions into the build files")
    if args.github_output:
        with open(args.github_output, "a") as out:
            out.write(f"changed={'true' if changed else 'false'}\nreleased={show(rel)}\n"
                      f"target={show(target)}\npatcher={target['patcher']}\ncli={target['cli']}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

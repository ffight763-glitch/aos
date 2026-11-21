#!/usr/bin/env python3
"""ahome update server.

publish:  python tools/serve_updates.py publish [notes]  -> copies the built APK into updates/ and writes latest.json
serve:    python tools/serve_updates.py serve            -> serves updates/ on port 8000

Phones on USB reach it as 127.0.0.1:8000 after `adb reverse tcp:8000 tcp:8000` (tools/push.cmd does all of it).
"""
import functools
import glob
import http.server
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UPDATES = os.path.join(ROOT, "updates")
APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")


def aapt2():
    sdk = os.path.join(os.environ["USERPROFILE"], "Android", "Sdk", "build-tools")
    found = sorted(glob.glob(os.path.join(sdk, "*", "aapt2.exe")))
    if not found:
        sys.exit("aapt2 not found in the Android SDK build-tools")
    return found[-1]


def publish():
    os.makedirs(UPDATES, exist_ok=True)
    out = subprocess.run([aapt2(), "dump", "badging", APK], capture_output=True, text=True, check=True).stdout
    m = re.search(r"versionCode='(\d+)' versionName='([^']*)'", out)
    if not m:
        sys.exit("could not read the version from the APK")
    shutil.copyfile(APK, os.path.join(UPDATES, "ahome.apk"))
    notes = sys.argv[2] if len(sys.argv) > 2 else "New ahome build."
    with open(os.path.join(UPDATES, "latest.json"), "w", encoding="utf-8") as f:
        json.dump({"versionCode": int(m.group(1)), "versionName": m.group(2), "apk": "ahome.apk", "notes": notes}, f)
    print("published", m.group(1), m.group(2))


def serve():
    os.makedirs(UPDATES, exist_ok=True)
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=UPDATES)
    print("serving", UPDATES, "on :8000")
    http.server.ThreadingHTTPServer(("0.0.0.0", 8000), handler).serve_forever()


if len(sys.argv) > 1 and sys.argv[1] == "publish":
    publish()
else:
    serve()

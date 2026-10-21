#!/usr/bin/env python3
"""The third-party licence inventory both apps show (D-146).

Writes `native/licences/licences.json`, which the iOS app bundles as a
resource and the Android app reads from its assets. Every component the
app ships is listed with the licence SKIPTI takes it under and the text
that licence requires the app to carry.

    python3 scripts/licences.py            # regenerate the committed file
    python3 scripts/licences.py --check    # gate leg: regenerate == committed

Where the list comes from (nothing is typed in by hand except where noted):
- the Rust core: `cargo metadata --offline` for the targets the shells
  build (aarch64-apple-ios; aarch64- and x86_64-linux-android), every crate
  reachable from skipti-core through normal dependencies. Proc-macro crates
  run in the compiler and ship nothing, so they are left out. The list is
  otherwise conservative: a crate only the bindings generator uses is
  listed although the linker drops its code.
- C code compiled into the core: SQLCipher and the SQLite it is built on
  (libsqlite3-sys's bundled sources), and OpenSSL on Android (openssl-src,
  vendored by D-91). Their licence text is read from those sources.
- Android: Gradle's resolved release runtime classpath per flavour
  (`:app:dependencies`, offline), licences read from each module's POM.
- Fonts, icons and data: declared below (FIXED_COMPONENTS), with each
  font's copyright read from its own name table.

A licence expression the script does not know makes it refuse, so a new
kind of licence is looked at by a person before it reaches the app.
"""
import hashlib
import json
import os
import plistlib
import re
import struct
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "native", "licences", "licences.json")

IOS_TARGETS = ["aarch64-apple-ios"]
ANDROID_TARGETS = ["aarch64-linux-android", "x86_64-linux-android"]
FLAVOURS = ["play"]  # D-191: the direct flavour is retired

GROUP_ASSETS = "Fonts and icons"  # D-200: the BIP-39 words left with the Recovery Kit
GROUP_CORE = "Shared core"
GROUP_ANDROID = "Android"
GROUP_IOS = "iPhone"

# D-191: the Swift packages LINKED into the iOS app (Firebase Auth's graph).
# Package.resolved also pins packages that are resolved but never linked
# (analytics, Firestore's, ads measurement); only these ship. A unit that adds
# a Firebase product adds its packages here, and ios_components() refuses a
# build whose intermediates show a package this list does not name.
SWIFT_RESOLVED = os.path.join(ROOT, "ios", "Skipti.xcodeproj", "project.xcworkspace", "xcshareddata",
                              "swiftpm", "Package.resolved")
SWIFT_CHECKOUTS = os.path.join(ROOT, "ios", "build", "SourcePackages", "checkouts")
SWIFT_INTERMEDIATES = os.path.join(ROOT, "ios", "build", "Build", "Intermediates.noindex")
# identity in Package.resolved -> (shown name, its *.build directory in the
# intermediates, its licence, its licence file in the checkout).
# D-193: Firestore adds abseil, gRPC (binary), LevelDB and nanopb.
SWIFT_LINKED = {
    "abseil-cpp-binary": ("Abseil", "abseil.build", "Apache-2.0", "LICENSE"),
    "firebase-ios-sdk": ("Firebase iOS SDK", "Firebase.build", "Apache-2.0", "LICENSE"),
    # D-196: Firebase Messaging links Google's data transport (its delivery
    # metrics, off: no BigQuery export) and Installations' Promises.
    "googledatatransport": ("GoogleDataTransport", "GoogleDataTransport.build", "Apache-2.0", "LICENSE"),
    "googleutilities": ("GoogleUtilities", "GoogleUtilities.build", "Apache-2.0", "LICENSE"),
    "grpc-binary": ("gRPC", "gRPC.build", "Apache-2.0", "LICENSE"),
    "gtm-session-fetcher": ("GTMSessionFetcher", "GTMSessionFetcher.build", "Apache-2.0", "LICENSE"),
    "interop-ios-for-google-sdks": ("Interop for Google SDKs", "InteropForGoogle.build", "Apache-2.0", "LICENSE"),
    "leveldb": ("LevelDB", "leveldb.build", "BSD-3-Clause", "LICENSE"),
    "nanopb": ("nanopb", "nanopb.build", "Zlib", "LICENSE.txt"),
    "promises": ("Promises", "Promises.build", "Apache-2.0", "LICENSE"),
}
# D-193: gRPC's binary links BoringSSL (its openssl_grpc framework), which is
# not a package of its own. Its LICENSE (OpenSSL, SSLeay, ISC and MIT parts)
# is kept verbatim from the commit gRPC v1.69.0 builds,
# google/boringssl@b8b3e6e11166719a8ebfa43c0cde9ad7d57a84f6.
BORINGSSL = ("BoringSSL (in gRPC)", "grpc-binary",
             os.path.join(ROOT, "native", "licences", "boringssl-LICENSE"))

# Which licence SKIPTI elects when a component offers a choice: the lowest
# rank wins. Apache-2.0 first because one copy of its text covers every
# crate that offers it; MIT next; Unlicense last (MIT is always offered
# beside it here, and MIT's notice is the more familiar one).
RANK = {
    "Apache-2.0": 0,
    "Apache-2.0 WITH LLVM-exception": 1,
    "MIT": 2,
    "BSD-3-Clause": 3,
    "ISC": 3,
    "Zlib": 3,
    "Unicode-3.0": 3,
    "MPL-2.0": 4,
    "Unlicense": 5,
}

# The marker each licence's own text must contain. A crate's licence file
# that does not match is refused rather than shown under the wrong name.
MARKERS = {
    "MIT": "Permission is hereby granted",
    "BSD-3-Clause": "Redistribution and use in source and binary forms",
    "ISC": "Permission to use, copy, modify, and/or distribute",
    "Zlib": "This software is provided 'as-is'",
    "Unicode-3.0": "UNICODE LICENSE V3",
    "MPL-2.0": "Mozilla Public License Version 2.0",
}

FILE_CANDIDATES = {
    "MIT": ["LICENSE-MIT", "LICENSE-MIT.md", "LICENSE-MIT.txt", "LICENSE", "LICENSE.md", "LICENSE.txt"],
    "BSD-3-Clause": ["LICENSE", "LICENSE.md", "LICENSE.txt", "LICENSE-BSD"],
    "ISC": ["LICENSE", "LICENSE.md", "LICENSE.txt"],
    "Zlib": ["LICENSE", "LICENSE-ZLIB", "LICENSE-ZLIB.md", "LICENSE.md", "LICENSE.txt"],
    "Unicode-3.0": ["LICENSE-UNICODE"],
    "MPL-2.0": ["LICENSE", "LICENSE.md", "LICENSE.txt"],
}

# Each component's own NOTICE file, which Apache-2.0 §4(d) says must travel
# with it.
NOTICE_NAMES = ["NOTICE", "NOTICE.md", "NOTICE.txt"]


class Refusal(Exception):
    """Something a person has to look at before the inventory can be written."""


def die(msg):
    raise Refusal(msg)


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def norm(text):
    """Line endings unified and trailing space dropped, so one licence
    copied with CRLF is not stored twice."""
    lines = [l.rstrip() for l in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    return "\n".join(lines).strip() + "\n"


class Texts:
    """Licence texts, stored once each and referred to by key."""

    def __init__(self):
        self.by_key = {}

    def add(self, text, key=None):
        text = norm(text)
        if key is None:
            key = "t" + hashlib.sha256(text.encode()).hexdigest()[:12]
        prior = self.by_key.get(key)
        if prior is not None and prior != text:
            die("two different texts for key " + key)
        self.by_key[key] = text
        return key


# --- SPDX expressions ------------------------------------------------------

def parse_spdx(expr):
    """Disjunctive normal form: a list of alternatives, each a list of
    licences that all apply. Handles OR, AND, WITH, parentheses, and the
    old slash form (`MIT/Apache-2.0`)."""
    expr = expr.replace("/", " OR ")
    tokens = re.findall(r"\(|\)|[A-Za-z0-9.\-+]+", expr)
    pos = 0

    def peek():
        return tokens[pos] if pos < len(tokens) else None

    def take():
        nonlocal pos
        if pos >= len(tokens):
            die("licence expression ends early: " + expr)
        pos += 1
        return tokens[pos - 1]

    def atom():
        t = take()
        if t == "(":
            v = disj()
            if take() != ")":
                die("unbalanced parentheses in " + expr)
            return v
        if peek() == "WITH":
            take()
            t = t + " WITH " + take()
        return [[t]]

    def conj():
        v = atom()
        while peek() == "AND":
            take()
            rhs = atom()
            v = [a + b for a in v for b in rhs]
        return v

    def disj():
        v = conj()
        while peek() == "OR":
            take()
            v = v + conj()
        return v

    result = disj()
    if pos != len(tokens):
        die("could not parse licence expression " + expr)
    return result


def elect(expr):
    """The alternative SKIPTI takes, as a sorted list of licence ids."""
    best = None
    for alt in parse_spdx(expr):
        for lic in alt:
            if lic not in RANK:
                break
        else:
            ranks = [RANK[l] for l in alt]
            score = (max(ranks), sum(ranks), len(alt))
            if best is None or score < best[0]:
                best = (score, sorted(set(alt)))
    if best is None:
        die("no known licence in '" + expr + "': review it, then add it to RANK")
    return best[1]


# --- Rust ------------------------------------------------------------------

def cargo_metadata(target):
    out = subprocess.run(
        ["cargo", "metadata", "--offline", "--format-version", "1", "--filter-platform", target],
        cwd=ROOT, capture_output=True, text=True,
    )
    if out.returncode != 0:
        die("cargo metadata failed for " + target + ":\n" + out.stderr)
    return json.loads(out.stdout)


def shipped_crates(meta):
    """Package ids reachable from skipti-core by normal dependencies,
    skipping proc-macro crates (they run inside the compiler)."""
    pkgs = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    root = next(p["id"] for p in meta["packages"] if p["name"] == "skipti-core")

    def is_proc_macro(pid):
        return any("proc-macro" in t["kind"] for t in pkgs[pid]["targets"])

    seen, stack = set(), [root]
    while stack:
        pid = stack.pop()
        if pid in seen:
            continue
        seen.add(pid)
        for dep in nodes[pid]["deps"]:
            if any(k["kind"] is None for k in dep["dep_kinds"]) and not is_proc_macro(dep["pkg"]):
                stack.append(dep["pkg"])
    seen.discard(root)
    return seen, pkgs


def licence_file(crate_dir, lic, name):
    for cand in FILE_CANDIDATES[lic]:
        path = os.path.join(crate_dir, cand)
        if os.path.isfile(path):
            text = read(path)
            if MARKERS[lic] in text:
                return text
    die("crate " + name + ": no " + lic + " licence file with the expected wording in " + crate_dir)


def notice_file(crate_dir):
    for cand in NOTICE_NAMES:
        path = os.path.join(crate_dir, cand)
        if os.path.isfile(path):
            return read(path)
    return None


def rust_components(texts, apache_key):
    per_shell = {}
    packages = {}
    for shell, targets in (("ios", IOS_TARGETS), ("android", ANDROID_TARGETS)):
        for target in targets:
            meta = cargo_metadata(target)
            ids, pkgs = shipped_crates(meta)
            packages.update(pkgs)
            per_shell.setdefault(shell, set()).update(ids)
            per_shell.setdefault("_meta_" + shell, meta)

    mpl = spdx_text("MPL-2.0")
    if mpl is None or MARKERS["MPL-2.0"] not in mpl:
        die("the MPL-2.0 text was not found in the local cargo registry")
    mpl_key = texts.add(mpl, "MPL-2.0")
    comps = []
    for pid in sorted(per_shell["ios"] | per_shell["android"],
                      key=lambda i: (packages[i]["name"], packages[i]["version"])):
        p = packages[pid]
        name, version, expr = p["name"], p["version"], p.get("license")
        if not expr:
            die("crate " + name + " declares no SPDX licence; review it by hand")
        crate_dir = os.path.dirname(p["manifest_path"])
        elected = elect(expr)
        keys = []
        for lic in elected:
            if lic.startswith("Apache-2.0"):
                keys.append(apache_key)
            elif lic == "MPL-2.0":
                # The uniffi crates ship no licence file; MPL-2.0 carries no
                # copyright line, so the canonical text is the whole of it.
                keys.append(mpl_key)
            else:
                keys.append(texts.add(licence_file(crate_dir, lic, name)))
        notice = notice_file(crate_dir)
        if notice is not None:
            keys.append(texts.add(notice))
        shells = []
        if pid in per_shell["ios"]:
            shells.append("ios")
        if pid in per_shell["android"]:
            shells += FLAVOURS
        comp = {
            "group": GROUP_CORE,
            "name": name,
            "version": version,
            "licence": " AND ".join(elected),
            "offered": expr,
            "shells": shells,
            "texts": keys,
        }
        if "MPL-2.0" in elected:
            comp["notice"] = (
                "Used unmodified. Its source code is available from crates.io as "
                + name + " " + version + "."
            )
        comps.append(comp)
    return comps, per_shell, packages


def crate_dir_named(packages, name):
    hits = [p for p in packages.values() if p["name"] == name]
    if len(hits) != 1:
        die("expected exactly one " + name + " package, found " + str(len(hits)))
    return os.path.dirname(hits[0]["manifest_path"]), hits[0]["version"]


def c_components(texts, apache_key, per_shell, packages):
    """C libraries compiled into the core."""
    comps = []
    sys_dir, _ = crate_dir_named(packages, "libsqlite3-sys")
    amalgamation = read(os.path.join(sys_dir, "sqlcipher", "sqlite3.c"))
    m = re.search(r"#define CIPHER_VERSION_NUMBER (\d+\.\d+\.\d+)", amalgamation)
    s = re.search(r"from SQLite\s*\n\*\* version (\d+\.\d+\.\d+)", amalgamation)
    if not m or not s:
        die("could not read the SQLCipher / SQLite versions from libsqlite3-sys")
    sqlcipher_text = read(os.path.join(sys_dir, "sqlcipher", "LICENSE"))
    if MARKERS["BSD-3-Clause"] not in sqlcipher_text or "Zetetic" not in sqlcipher_text:
        die("SQLCipher's licence file changed; review it")
    all_shells = ["ios"] + FLAVOURS
    comps.append({
        "group": GROUP_CORE,
        "name": "SQLCipher",
        "version": m.group(1),
        "licence": "BSD-3-Clause",
        "shells": all_shells,
        "notice": "The encrypted database your ledger is stored in. Built from the "
                  "sources bundled in libsqlite3-sys.",
        "texts": [texts.add(sqlcipher_text)],
    })
    comps.append({
        "group": GROUP_CORE,
        "name": "SQLite",
        "version": s.group(1),
        "licence": "Public domain",
        "shells": all_shells,
        "notice": "SQLite is in the public domain. SQLCipher is built on it.",
        "texts": [],
    })

    # OpenSSL: Android only (Apple targets use CommonCrypto; D-91).
    openssl = [p for p in packages.values() if p["name"] == "openssl-src"]
    android_meta = per_shell["_meta_android"]
    if not any(p["name"] == "openssl-src" for p in android_meta["packages"]):
        die("openssl-src is not in the Android build any more; review the OpenSSL entry")
    if len(openssl) != 1:
        die("expected one openssl-src package")
    src_dir = os.path.dirname(openssl[0]["manifest_path"])
    ver = openssl[0]["version"].split("+")[-1]
    header = read(os.path.join(src_dir, "openssl", "crypto", "mem.c"))
    c = re.search(r"Copyright (\d{4}-\d{4}) The OpenSSL Project Authors", header)
    lic = read(os.path.join(src_dir, "openssl", "LICENSE.txt"))
    if "Apache License" not in lic or "Version 2.0" not in lic:
        die("OpenSSL's licence is no longer Apache-2.0; review it")
    if not c:
        die("could not read OpenSSL's copyright line")
    comps.append({
        "group": GROUP_CORE,
        "name": "OpenSSL",
        "version": ver,
        "licence": "Apache-2.0",
        "shells": FLAVOURS,
        "notice": "Copyright " + c.group(1) + " The OpenSSL Project Authors. "
                  "Supplies SQLCipher's cryptography on Android.",
        "texts": [apache_key],
    })
    return comps


# --- Android ---------------------------------------------------------------

POM_NS = "{http://maven.apache.org/POM/4.0.0}"
GRADLE_CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

# POM licence names, as their publishers spell them, to SPDX ids.
POM_LICENCES = {
    "The Apache Software License, Version 2.0": "Apache-2.0",
    "The Apache License, Version 2.0": "Apache-2.0",
    "Apache-2.0": "Apache-2.0",
    "Apache License, Version 2.0": "Apache-2.0",
    "Apache 2.0": "Apache-2.0",
    "LGPL-2.1-or-later": "LGPL-2.1-or-later",
    "Android Software Development Kit License": "Android-SDK",
    "BSD-3-Clause": "BSD-3-Clause",
    # D-193: re2j (gRPC, under Firestore) names Go's licence, which is the
    # BSD 3-clause text; its own LICENSE is kept in native/licences/.
    "Go License": "BSD-3-Clause",
    "The MIT License": "MIT",
    "MIT license": "MIT",
    "Play Core Software Development Kit Terms of Service": "Google-Terms",
    "Play Integrity API Terms of Service": "Google-Terms",
}

# D-191: Google libraries under Google's own terms (Firebase Auth pulls Play
# Core and Play Integrity): named, with the link their POM gives.
GOOGLE_TERMS = {
    "Play Core Software Development Kit Terms of Service": "developer.android.com/guide/playcore/license",
    "Play Integrity API Terms of Service": "developer.android.com/google/play/integrity/overview#tos",
}

# D-191: a BSD-3-Clause component must show ITS copyright notice, which a POM
# does not carry. Each one is listed here with its upstream LICENSE kept
# verbatim in native/licences/; any other BSD-3-Clause component refuses.
# androidx.datastore's external-protobuf is Google's protobuf, repackaged
# (Firebase Auth pulls it); the text is protocolbuffers/protobuf's LICENSE.
BSD_TEXTS = {
    "androidx.datastore:datastore-preferences-external-protobuf":
        os.path.join(ROOT, "native", "licences", "protobuf-LICENSE"),
    # D-193: Firestore's own protobuf runtime, the same upstream project.
    "com.google.protobuf:protobuf-javalite":
        os.path.join(ROOT, "native", "licences", "protobuf-LICENSE"),
    "com.google.re2j:re2j": os.path.join(ROOT, "native", "licences", "re2j-LICENSE"),
    # MIT, the same rule (the copyright line is the notice): the jar's own
    # META-INF/LICENSE.txt, verbatim.
    "org.checkerframework:checker-qual": os.path.join(ROOT, "native", "licences", "checker-qual-LICENSE"),
    # Its jar carries no licence: mojohaus/animal-sniffer's LICENSE at tag 1.23.
    "org.codehaus.mojo:animal-sniffer-annotations":
        os.path.join(ROOT, "native", "licences", "animal-sniffer-LICENSE"),
}


def gradle_coordinates(configuration):
    java_home = os.environ.get("JAVA_HOME") or \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    env = dict(os.environ, JAVA_HOME=java_home)
    out = subprocess.run(
        ["./gradlew", "-q", "--offline", ":app:dependencies", "--configuration", configuration],
        cwd=os.path.join(ROOT, "android"), capture_output=True, text=True, env=env,
    )
    if out.returncode != 0:
        die("gradle dependencies failed for " + configuration + ":\n" + out.stderr[-2000:])
    coords = set()
    for line in out.stdout.splitlines():
        # D-191: a BoM-managed dependency has no version of its own and reads
        # `group:artifact -> 24.2.0`; the old pattern required `g:a:v` and
        # silently skipped every Firebase library.
        m = re.search(r"--- ([\w.\-]+):([\w.\-]+)(?::(\S+))?(?: -> (\S+))?", line)
        if not m or line.rstrip().endswith("(c)"):
            continue
        g, a, v, resolved = m.groups()
        if not (resolved or v):
            die("no version for " + g + ":" + a + " in: " + line.strip())
        coords.add((g, a, resolved or v))
    if not coords:
        die("no dependencies parsed for " + configuration)
    return coords


def collapse(coords):
    """Drop BOMs (they ship nothing) and the per-platform halves of
    multiplatform modules (`ui-android` beside `ui`)."""
    keep = set()
    for g, a, v in coords:
        if a.endswith("-bom"):
            continue
        base = re.sub(r"-(android|jvm)$", "", a)
        if base != a and (g, base, v) in coords:
            continue
        keep.add((g, a, v))
    return keep


def pom_path(g, a, v):
    d = os.path.join(GRADLE_CACHE, g, a, v)
    if os.path.isdir(d):
        for h in sorted(os.listdir(d)):
            p = os.path.join(d, h, a + "-" + v + ".pom")
            if os.path.isfile(p):
                return p
    return None


# D-191: POMs that declare no licence at all, checked by hand. javax.inject 1
# (JSR-330, pulled by Firebase) is Apache-2.0: every source file's header in
# github.com/javax-inject/javax-inject says so (read 2026-10-01; the
# repository has no LICENSE file).
UNDECLARED = {
    ("javax.inject", "javax.inject", "1"): ["Apache-2.0"],
}


def pom_licences(g, a, v, depth=0):
    if (g, a, v) in UNDECLARED:
        return UNDECLARED[(g, a, v)]
    path = pom_path(g, a, v)
    if path is None:
        # D-173: Gradle's periodic cache clean-up can delete a POM while
        # keeping the metadata it resolves from, so a build never fetches it
        # again (seen 2026-09-26 for androidx.activity 1.13.0).
        die("no POM cached for " + ":".join((g, a, v)) + " (run make android-app first; if Gradle's "
            "cache clean-up removed it, re-fetch with: cd android && ./gradlew :app:dependencies "
            "--configuration playReleaseRuntimeClasspath --refresh-dependencies)")
    root = ET.parse(path).getroot()
    lics = [(l.findtext(POM_NS + "name") or "").strip() for l in root.iter(POM_NS + "license")]
    if lics:
        return lics
    parent = root.find(POM_NS + "parent")
    if parent is not None and depth < 5:
        return pom_licences(parent.findtext(POM_NS + "groupId"),
                            parent.findtext(POM_NS + "artifactId"),
                            parent.findtext(POM_NS + "version"), depth + 1)
    die("no licence declared for " + ":".join((g, a, v)))


def android_components(texts, apache_key):
    by_flavour = {f: collapse(gradle_coordinates(f + "ReleaseRuntimeClasspath")) for f in FLAVOURS}
    comps = []
    for g, a, v in sorted(by_flavour["play"]):
        names = pom_licences(g, a, v)
        spdx = []
        for n in names:
            if n not in POM_LICENCES:
                die("unknown POM licence '" + n + "' on " + ":".join((g, a, v)) + "; review it")
            spdx.append(POM_LICENCES[n])
        shells = [f for f in FLAVOURS if (g, a, v) in by_flavour[f]]
        comp = {"group": GROUP_ANDROID, "name": g + ":" + a, "version": v, "shells": shells}
        if spdx == ["Android-SDK"]:
            comp["licence"] = "Android Software Development Kit License"
            comp["notice"] = ("Google's library, under Google's own terms: "
                              "developer.android.com/studio/terms.html")
            comp["texts"] = []
        elif spdx == ["Google-Terms"]:
            comp["licence"] = names[0]
            comp["notice"] = "Google's library, under Google's own terms: " + GOOGLE_TERMS[names[0]]
            comp["texts"] = []
        elif spdx in (["BSD-3-Clause"], ["MIT"]):
            path = BSD_TEXTS.get(g + ":" + a) or die(spdx[0] + " " + g + ":" + a + " has no licence text on file")
            comp["licence"] = spdx[0]
            comp["texts"] = [texts.add(read(path))]
        elif "Apache-2.0" in spdx:
            # JNA offers LGPL-2.1-or-later OR Apache-2.0; SKIPTI takes Apache-2.0.
            comp["licence"] = "Apache-2.0"
            if len(spdx) > 1:
                comp["offered"] = " OR ".join(spdx)
            comp["texts"] = [apache_key]
        else:
            die("no licence SKIPTI can elect for " + ":".join((g, a, v)) + ": " + str(spdx))
        comps.append(comp)
    return comps


def ios_components(texts, apache_key):
    """D-191: the Swift packages linked into the iOS app (D-193: with their
    own licences, not only Apache-2.0)."""
    pins = {p["identity"]: p for p in json.loads(read(SWIFT_RESOLVED))["pins"]}
    if os.path.isdir(SWIFT_INTERMEDIATES):
        known = {entry[1] for entry in SWIFT_LINKED.values()}
        built = {d for d in os.listdir(SWIFT_INTERMEDIATES)
                 if d.endswith(".build") and not d.startswith("Skipti")}
        extra = built - known
        if extra:
            die("Swift packages built into the app but not listed in SWIFT_LINKED: " + ", ".join(sorted(extra)))
    comps = []
    for identity, (name, _, licence, licence_file) in sorted(SWIFT_LINKED.items()):
        pin = pins.get(identity) or die("Package.resolved does not pin " + identity)
        version = pin["state"].get("version") or die(identity + " is not pinned to a version")
        checkout = [d for d in (os.listdir(SWIFT_CHECKOUTS) if os.path.isdir(SWIFT_CHECKOUTS) else [])
                    if d.lower() == pin["location"].rstrip("/").split("/")[-1].removesuffix(".git").lower()]
        if not checkout:
            die("no checkout of " + identity + " (run make ios-app first)")
        text = read(os.path.join(SWIFT_CHECKOUTS, checkout[0], licence_file))
        if licence == "Apache-2.0":
            if "Apache License" not in text or "Version 2.0, January 2004" not in text:
                die(identity + "'s LICENSE is not the Apache License 2.0")
            key = apache_key
        else:
            if MARKERS[licence] not in text:
                die(identity + "'s " + licence_file + " is not the " + licence + " text")
            key = texts.add(text)
        comps.append({"group": GROUP_IOS, "name": name, "version": version, "licence": licence,
                      "shells": ["ios"], "texts": [key]})
    name, carrier, path = BORINGSSL
    text = read(path)
    if "OpenSSL License" not in text or "Original SSLeay License" not in text:
        die("the BoringSSL LICENSE on file is not BoringSSL's")
    comps.append({"group": GROUP_IOS, "name": name, "version": pins[carrier]["state"]["version"],
                  "licence": "OpenSSL AND SSLeay AND ISC AND MIT", "shells": ["ios"],
                  "texts": [texts.add(text)]})
    return comps


# --- Fonts, icons and data -------------------------------------------------

def font_names(path):
    """The 'name' table of a TrueType font, Windows English records."""
    with open(path, "rb") as f:
        data = f.read()
    count = struct.unpack(">H", data[4:6])[0]
    for i in range(count):
        tag, _, off, _ = struct.unpack(">4sIII", data[12 + 16 * i:28 + 16 * i])
        if tag == b"name":
            break
    else:
        die("no name table in " + path)
    _, n, storage = struct.unpack(">HHH", data[off:off + 6])
    out = {}
    for j in range(n):
        pid, eid, lid, nid, ln, so = struct.unpack(">HHHHHH", data[off + 6 + 12 * j:off + 18 + 12 * j])
        if pid == 3 and eid in (0, 1, 10) and lid == 0x409:
            raw = data[off + storage + so:off + storage + so + ln]
            out.setdefault(nid, raw.decode("utf-16-be"))
    return out


def spdx_text(ident):
    """A canonical licence text from the `spdx` crate's data files in the
    local cargo registry (read, never built). Used only for texts that carry
    no copyright line of their own."""
    base = os.path.expanduser("~/.cargo/registry/src")
    for reg in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        for d in sorted(os.listdir(os.path.join(base, reg)), reverse=True):
            if d.startswith("spdx-"):
                p = os.path.join(base, reg, d, "src", "text", "licenses", ident)
                if os.path.isfile(p):
                    t = read(p)
                    m = re.match(r'^r#*"(.*)"#*\s*$', t, re.S)
                    return m.group(1) if m else t
    # D-173: cargo's automatic cache clean-up deletes unpacked sources it has
    # not used lately while keeping the downloaded .crate archives, and one
    # run removed spdx's (the gate failed "not found"). Read the same file out
    # of the newest cached archive instead: a gzip tar, read, never unpacked
    # to disk or built.
    cache = os.path.expanduser("~/.cargo/registry/cache")
    archives = []
    for reg in sorted(os.listdir(cache)) if os.path.isdir(cache) else []:
        for f in os.listdir(os.path.join(cache, reg)):
            m = re.match(r"^spdx-(\d+)\.(\d+)\.(\d+)\.crate$", f)
            if m:
                archives.append((tuple(map(int, m.groups())), os.path.join(cache, reg, f)))
    for version, path in sorted(archives, reverse=True):
        with tarfile.open(path, "r:gz") as tar:
            name = "spdx-%d.%d.%d/src/text/licenses/%s" % (version + (ident,))
            try:
                member = tar.getmember(name)
            except KeyError:
                continue
            t = tar.extractfile(member).read().decode("utf-8")
            m = re.match(r'^r#*"(.*)"#*\s*$', t, re.S)
            return m.group(1) if m else t
    return None


# Lucide's LICENSE (github.com/lucide-icons/lucide, main, read 2026-09-21):
# ISC for Lucide, and MIT for the icons derived from Feather. Of the twenty
# drawings SKIPTI ships, `plus` is on Lucide's Feather-derived list; D-217
# (read 2026-10-15): so are `chevron-left` and `chevron-right`; D-220 (read
# 2026-10-16, lucide-static v1.58.0): so are `search` and `check`; D-222 (read
# 2026-10-16, lucide-static v1.58.0): so are `download` and `info`, while
# `sun` and `shield` are not (ISC only); D-223 (read 2026-10-17, lucide-static
# v1.59.0): `inbox` is not (ISC only); D-224 (read 2026-10-17, lucide-static
# v1.59.0): `vibrate` is not (ISC only); D-225 (read 2026-10-17, lucide-static
# v1.59.0): `x` and `lock` are (ISC and MIT); D-231 (read 2026-10-19, lucide-static
# v1.59.0): `sparkles` is not (ISC only; Feather has no sparkles).
LUCIDE_ISC = """ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
"""

MIT_BODY = """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""

# D-148: IBM's own files, from github.com/IBM/plex release @ibm/plex-mono
# 2.5.0 (ibm-plex-mono.zip, fonts/complete/ttf/), read 2026-09-22.
PLEX_SHA256 = {
    "IBMPlexMono-Regular.ttf": "7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43",
    "IBMPlexMono-Medium.ttf": "98fbd727aae340b236955879dabed4d991aac9e8e90b3b2a67ce4a59221cc97c",
}
# D-215: IBM Plex Sans, IBM's own files, from github.com/IBM/plex release
# @ibm/plex-sans@1.1.0 (ibm-plex-sans.zip, sha256
# fb365d910566e6d199cc2c15579a7dd9a267128e18431a394ed81f1970c69200),
# "Version 3.005". The body face of both shells.
PLEX_SANS_SHA256 = {
    "IBMPlexSans-Regular.ttf": "975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5",
    "IBMPlexSans-Medium.ttf": "331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907",
    "IBMPlexSans-SemiBold.ttf": "a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1",
    "IBMPlexSans-Bold.ttf": "9e6c74a889a700d707613d24548fe4ffa6bc59559a0689d2cf9e133bdcdafb2f",
}
# D-215: Vardr Runes Carved Regular, Vardr Labs' own font, from release v1.000
# of the private vardrlabs/fonts repo (fonts/ttf/VardrRunes-CarvedRegular.ttf).
# Its OFL 1.1 declares the Reserved Font Name "Vardr". Norse's runes on both
# shells (Android's copy is in ANDROID_FONT_COPIES, D-215 B5).
VARDR_RUNES_SHA256 = {
    "VardrRunes-CarvedRegular.ttf": "6d61b17c4929925f5aeb58a4b5541c9524de0cb531ea0c055043b6267b04cf01",
}
# D-212: Cormorant SC from github.com/google/fonts (ofl/cormorantsc/),
# Version 4.000, unmodified, read 2026-10-14. Its OFL declares no Reserved
# Font Name. Norse's display and section-label face.
CORMORANT_SHA256 = {
    "CormorantSC-SemiBold.ttf": "648cd1c932871e4e8e1d147b593315409e8ea82282f88e9397e2cceae83d065b",
    "CormorantSC-Bold.ttf": "78f70c79b5c0a1e66641d4b4a2e2ed32e1a45774b3de94e56f8de68510219177",
}
# D-215 fix wave (B3b-m1): Space Grotesk as it ships (the variable file in
# the tree since c989f5b, unchanged by D-174 and D-212), pinned like the rest
# so a changed or second Space Grotesk file fails the leg.
SPACE_GROTESK_SHA256 = {
    "SpaceGrotesk.ttf": "1aab0c5e196cd52b38b0398c6376907d7d68229d6a54eca5fcf0f4c114983cbb",
}
ANDROID_FONT_COPIES = {
    "CormorantSC-SemiBold.ttf": "cormorantsc_semibold.ttf",
    "CormorantSC-Bold.ttf": "cormorantsc_bold.ttf",
    "IBMPlexMono-Regular.ttf": "ibmplexmono_regular.ttf",
    "IBMPlexMono-Medium.ttf": "ibmplexmono_medium.ttf",
    "IBMPlexSans-Regular.ttf": "ibmplexsans_regular.ttf",
    "IBMPlexSans-Medium.ttf": "ibmplexsans_medium.ttf",
    "IBMPlexSans-SemiBold.ttf": "ibmplexsans_semibold.ttf",
    "IBMPlexSans-Bold.ttf": "ibmplexsans_bold.ttf",
    "SpaceGrotesk.ttf": "spacegrotesk.ttf",
    "VardrRunes-CarvedRegular.ttf": "vardrrunes_carvedregular.ttf",
}

# D-215: the one table that says which inventory entry a bundled font file
# belongs to: filename prefix -> (inventory name, the table that pins its
# bytes). A font file on disk that no row here matches, or that its row's
# table does not pin, fails the licence leg: a face cannot be bundled on both
# shells with no entry on the Licences page. Space Grotesk's prefix carries
# the dot, so only "SpaceGrotesk.<ext>" belongs to it (fix wave, B3b-m1).
FONT_FAMILIES = {
    "SpaceGrotesk.": ("Space Grotesk", SPACE_GROTESK_SHA256),
    "IBMPlexMono-": ("IBM Plex Mono", PLEX_SHA256),
    "IBMPlexSans-": ("IBM Plex Sans", PLEX_SANS_SHA256),
    "CormorantSC-": ("Cormorant SC", CORMORANT_SHA256),
    "VardrRunes-": ("Vardr Runes", VARDR_RUNES_SHA256),
}
FONT_EXTENSIONS = (".ttf", ".otf")


def font_problems(native, res, ui_app_fonts, inventory_names):
    """Every way the bundled font files and the inventory can disagree, as
    messages naming the file. Pure: the caller supplies the listings."""
    problems = []
    for name in sorted(native):
        row = next((v for k, v in FONT_FAMILIES.items() if name.startswith(k)), None)
        if row is None:
            problems.append("native/fonts/" + name + " matches no family in FONT_FAMILIES; add its "
                            "family and an inventory entry in scripts/licences.py")
            continue
        family, pins = row
        if family not in inventory_names:
            problems.append("native/fonts/" + name + " belongs to " + family
                            + ", which has no entry in the licence inventory")
        if pins is not None and name not in pins:
            problems.append("native/fonts/" + name + " is not pinned by the " + family
                            + " sha256 table; pin its bytes in scripts/licences.py")
    # D-215 fix wave (B4-m5): every bundled font ships on both phones.
    for name in sorted(native):
        if name not in ANDROID_FONT_COPIES:
            problems.append("native/fonts/" + name + " has no Android copy in ANDROID_FONT_COPIES; "
                            "every bundled font ships on both phones")
    copies = set(ANDROID_FONT_COPIES.values())
    for name in sorted(res):
        if name not in copies:
            problems.append("android res/font/" + name + " is not a value in ANDROID_FONT_COPIES; "
                            "add its native/fonts twin there")
    for name in sorted(ANDROID_FONT_COPIES):
        if name not in native:
            problems.append("ANDROID_FONT_COPIES names native/fonts/" + name + ", which does not exist")
    listed = set(ui_app_fonts)
    for name in sorted(listed - set(native)):
        problems.append("Info.plist UIAppFonts lists " + name + ", which is not in native/fonts/")
    for name in sorted(set(native) - listed):
        problems.append("native/fonts/" + name + " is not listed in Info.plist UIAppFonts")
    return problems


def check_bundled_fonts(inventory_names):
    fonts = os.path.join(ROOT, "native", "fonts")
    res = os.path.join(ROOT, "android", "app", "src", "main", "res", "font")
    with open(os.path.join(ROOT, "ios", "Skipti", "Info.plist"), "rb") as f:
        ui_app_fonts = plistlib.load(f).get("UIAppFonts", [])
    problems = font_problems(
        [n for n in os.listdir(fonts) if n.lower().endswith(FONT_EXTENSIONS)],
        [n for n in os.listdir(res) if n.lower().endswith(FONT_EXTENSIONS)],
        ui_app_fonts, inventory_names)
    if problems:
        die("; ".join(problems))


def sha256_file(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


FEATHER_MIT = "The MIT License (MIT)\n\nCopyright (c) 2013-present Cole Bemis\n\n" + MIT_BODY


def fixed_components(texts):
    ofl = spdx_text("OFL-1.1")
    if ofl is None or "SIL OPEN FONT LICENSE" not in ofl or "Version 1.1" not in ofl:
        die("the SIL Open Font License 1.1 text was not found in the local cargo registry")
    ofl_key = texts.add(ofl, "OFL-1.1")
    fonts = os.path.join(ROOT, "native", "fonts")
    grotesk = font_names(os.path.join(fonts, "SpaceGrotesk.ttf"))
    # D-148: Plex Mono must be IBM's own files. The OFL reserves the name
    # "Plex", and a trimmed (modified) copy may not keep it; the Google Fonts
    # subset this replaced did. Android's res/font copies must be the same
    # bytes as native/fonts for every face.
    for name, sha in PLEX_SHA256.items():
        if sha256_file(os.path.join(fonts, name)) != sha:
            die(name + " is not IBM's unmodified file (@ibm/plex-mono 2.5.0); a modified copy may not be called Plex")
    for name, sha in PLEX_SANS_SHA256.items():
        if sha256_file(os.path.join(fonts, name)) != sha:
            die(name + " is not IBM's unmodified file (@ibm/plex-sans 1.1.0); a modified copy may not be called Plex")
    for name, sha in VARDR_RUNES_SHA256.items():
        if sha256_file(os.path.join(fonts, name)) != sha:
            die(name + " is not the vardrlabs/fonts v1.000 release file; change the font there and copy the release file")
    for name, sha in CORMORANT_SHA256.items():
        if sha256_file(os.path.join(fonts, name)) != sha:
            die(name + " is not the google/fonts file (Cormorant SC 4.000) D-212 recorded")
    for name, sha in SPACE_GROTESK_SHA256.items():
        if sha256_file(os.path.join(fonts, name)) != sha:
            die(name + " is not the Space Grotesk file the apps have shipped since c989f5b")
    res = os.path.join(ROOT, "android", "app", "src", "main", "res", "font")
    for ios_name, android_name in ANDROID_FONT_COPIES.items():
        if sha256_file(os.path.join(fonts, ios_name)) != sha256_file(os.path.join(res, android_name)):
            die("android res/font/" + android_name + " differs from native/fonts/" + ios_name)
    plex = font_names(os.path.join(fonts, "IBMPlexMono-Regular.ttf"))
    plex_medium = font_names(os.path.join(fonts, "IBMPlexMono-Medium.ttf"))
    if plex[0] != plex_medium[0] or plex[5] != plex_medium[5]:
        die("the two IBM Plex Mono cuts disagree on copyright or version")
    sans = font_names(os.path.join(fonts, "IBMPlexSans-Regular.ttf"))
    for cut in ("Medium", "SemiBold", "Bold"):
        other = font_names(os.path.join(fonts, "IBMPlexSans-" + cut + ".ttf"))
        if sans[0] != other[0] or sans[5] != other[5]:
            die("the IBM Plex Sans cuts disagree on copyright or version")
    runes = font_names(os.path.join(fonts, "VardrRunes-CarvedRegular.ttf"))
    if "Reserved Font Name \"Vardr\"" not in runes.get(0, ""):
        die("Vardr Runes no longer declares its Reserved Font Name in its copyright line")
    cormorant = font_names(os.path.join(fonts, "CormorantSC-SemiBold.ttf"))
    cormorant_bold = font_names(os.path.join(fonts, "CormorantSC-Bold.ttf"))
    if cormorant[0] != cormorant_bold[0] or cormorant[5] != cormorant_bold[5]:
        die("the two Cormorant SC cuts disagree on copyright or version")
    for face in (grotesk, plex, sans, runes, cormorant):
        if "SIL Open Font License, Version 1.1" not in face.get(13, ""):
            die("a bundled font no longer says it is under the OFL 1.1")
    all_shells = ["ios"] + FLAVOURS
    return [
        {
            "group": GROUP_ASSETS,
            "name": "Space Grotesk",
            "version": grotesk[5].replace("Version ", ""),
            "licence": "OFL-1.1",
            "shells": all_shells,
            "notice": grotesk[0],
            "texts": [ofl_key],
        },
        {
            "group": GROUP_ASSETS,
            "name": "IBM Plex Mono",
            "version": plex[5].replace("Version ", ""),
            "licence": "OFL-1.1",
            "shells": all_shells,
            # The name table says only "All rights reserved"; the release's
            # LICENSE.txt adds the Reserved Font Name, which the licence makes
            # part of the notice.
            "notice": plex[0] + " Reserved Font Name \"Plex\". IBM's unmodified Regular and Medium "
                      "(release @ibm/plex-mono 2.5.0).",
            "texts": [ofl_key],
        },
        {
            "group": GROUP_ASSETS,
            "name": "IBM Plex Sans",
            "version": sans[5].replace("Version ", ""),
            "licence": "OFL-1.1",
            "shells": all_shells,
            # As for Plex Mono: the name table omits the Reserved Font Name
            # that the release's LICENSE.txt declares.
            "notice": sans[0] + " Reserved Font Name \"Plex\". IBM's unmodified Regular, Medium, "
                      "SemiBold and Bold (release @ibm/plex-sans 1.1.0).",
            "texts": [ofl_key],
        },
        {
            "group": GROUP_ASSETS,
            "name": "Vardr Runes",
            "version": runes[5].replace("Version ", ""),
            "licence": "OFL-1.1",
            "shells": all_shells,
            # The name table's copyright line already carries the Reserved Font Name.
            "notice": runes[0] + " Vardr Labs' own font, Carved Regular, release v1.000.",
            "texts": [ofl_key],
        },
        {
            "group": GROUP_ASSETS,
            "name": "Cormorant SC",
            "version": cormorant[5].replace("Version ", ""),
            "licence": "OFL-1.1",
            "shells": all_shells,
            "notice": cormorant[0] + ". SemiBold and Bold, unmodified (google/fonts, ofl/cormorantsc).",
            "texts": [ofl_key],
        },
        {
            "group": GROUP_ASSETS,
            "name": "Lucide icons",
            "version": "",
            "licence": "ISC AND MIT",
            "shells": all_shells,
            "notice": "Twenty drawings: wallet, users, receipt, plus, circle-check, "
                      "user-round, user-plus, chevron-left, chevron-right, search, check, sun, shield, download, "
                      "info, inbox, vibrate, x, lock and sparkles. Lucide's plus, chevron-left, chevron-right, search, check, download, "
                      "info, x and lock come from Feather, under the MIT License.",
            "texts": [texts.add(LUCIDE_ISC), texts.add(FEATHER_MIT)],
        },
        {
            "group": GROUP_ASSETS,
            "name": "Sign in with Google button and G",
            "version": "",
            "licence": "Google brand asset",
            "shells": ["ios", "play"],
            "notice": "Google's artwork and trademark (the sign-in button, and the "
                      "round G beside \"Signed in with Google\"), used unmodified as "
                      "Google's sign-in branding guidelines permit. It is not covered "
                      "by SKIPTI's licence.",
            "texts": [],
        },
    ]


# --- Assembly --------------------------------------------------------------

def build():
    texts = Texts()
    apache = read(os.path.join(ROOT, "LICENSE"))
    if "Apache License" not in apache or "Version 2.0, January 2004" not in apache:
        die("LICENSE is not the Apache License 2.0 text")
    apache_key = texts.add(apache, "Apache-2.0")

    comps = fixed_components(texts)
    check_bundled_fonts({c["name"] for c in comps})
    rust, per_shell, packages = rust_components(texts, apache_key)
    comps += c_components(texts, apache_key, per_shell, packages)
    comps += rust
    comps += android_components(texts, apache_key)
    comps += ios_components(texts, apache_key)

    used = {k for c in comps for k in c["texts"]}
    unused = set(texts.by_key) - used
    if unused:
        die("texts stored but never referenced: " + ", ".join(sorted(unused)))
    doc = {
        "format": 1,
        "about": "Generated by scripts/licences.py. Do not edit by hand.",
        "groups": [GROUP_ASSETS, GROUP_CORE, GROUP_ANDROID, GROUP_IOS],
        "components": comps,
        "texts": dict(sorted(texts.by_key.items())),
    }
    return json.dumps(doc, indent=1, ensure_ascii=False, sort_keys=False) + "\n"


def self_test():
    """The rules the inventory rests on, checked before every --check."""
    cases = {
        "MIT OR Apache-2.0": ["Apache-2.0"],
        "MIT/Apache-2.0": ["Apache-2.0"],
        "(MIT OR Apache-2.0) AND Unicode-3.0": ["Apache-2.0", "Unicode-3.0"],
        "Unlicense OR MIT": ["MIT"],
        "Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT": ["Apache-2.0"],
        "MPL-2.0": ["MPL-2.0"],
        "MIT OR GPL-3.0-only": ["MIT"],
    }
    for expr, want in cases.items():
        got = elect(expr)
        if got != want:
            raise AssertionError("elect(%r) = %r, want %r" % (expr, got, want))
    for refused in ("GPL-3.0-only", "MIT AND GPL-3.0-only", "(MIT"):
        try:
            elect(refused)
        except Refusal:
            continue
        raise AssertionError("elect(%r) was accepted; it must refuse" % refused)
    got = collapse({("g", "ui", "1"), ("g", "ui-android", "1"), ("g", "x-bom", "1"),
                    ("g", "y-jvm", "1"), ("g", "z-android", "2"), ("g", "z", "1")})
    want = {("g", "ui", "1"), ("g", "y-jvm", "1"), ("g", "z-android", "2"), ("g", "z", "1")}
    if got != want:
        raise AssertionError("collapse() = %r, want %r" % (sorted(got), sorted(want)))
    # D-215: the font rules. A clean listing passes; each way to bundle a font
    # with no entry is named.
    native = set(ANDROID_FONT_COPIES)
    res = set(ANDROID_FONT_COPIES.values())
    names = {v[0] for v in FONT_FAMILIES.values()}
    if font_problems(native, res, native, names):
        raise AssertionError("font_problems() refused a consistent set of fonts")
    font_cases = {
        "no family": (native | {"Zz-Test.ttf"}, res, native | {"Zz-Test.ttf"}, names, "matches no family"),
        "unpinned": (native | {"IBMPlexSans-Black.ttf"}, res, native | {"IBMPlexSans-Black.ttf"}, names,
                     "is not pinned"),
        "no entry": (native, res, native, names - {"Cormorant SC"}, "has no entry in the licence inventory"),
        "runes unpinned": (native | {"VardrRunes-CarvedBold.ttf"}, res, native | {"VardrRunes-CarvedBold.ttf"}, names,
                           "is not pinned by the Vardr Runes sha256 table"),
        "android extra": (native, res | {"zz_test.ttf"}, native, names, "not a value in ANDROID_FONT_COPIES"),
        "plist short": (native, res, native - {"SpaceGrotesk.ttf"}, names, "is not listed in Info.plist"),
        "plist extra": (native, res, native | {"Zz-Test.ttf"}, names, "which is not in native/fonts/"),
        "grotesk lookalike": (native | {"SpaceGroteskX.ttf"}, res, native | {"SpaceGroteskX.ttf"}, names,
                              "SpaceGroteskX.ttf matches no family"),
        "grotesk unpinned": (native | {"SpaceGrotesk.otf"}, res, native | {"SpaceGrotesk.otf"}, names,
                             "is not pinned by the Space Grotesk sha256 table"),
        "no android copy": (native | {"Zz-Test.ttf"}, res, native | {"Zz-Test.ttf"}, names,
                            "Zz-Test.ttf has no Android copy in ANDROID_FONT_COPIES"),
    }
    for label, (n, r, u, inv, want) in font_cases.items():
        if not any(want in p for p in font_problems(n, r, u, inv)):
            raise AssertionError("font_problems() missed the %s case (%r)" % (label, want))
    grotesk = font_names(os.path.join(ROOT, "native", "fonts", "SpaceGrotesk.ttf"))
    if not grotesk.get(0, "").startswith("Copyright 2020 The Space Grotesk Project Authors"):
        raise AssertionError("font_names() misread Space Grotesk's copyright: %r" % grotesk.get(0))


def main():
    try:
        run()
    except Refusal as e:
        sys.stderr.write("licences.py: " + str(e) + "\n")
        sys.exit(1)


def run():
    check = "--check" in sys.argv[1:]
    if check:
        self_test()
    fresh = build()
    if check:
        try:
            committed = read(OUT)
        except FileNotFoundError:
            die(OUT + " does not exist; run python3 scripts/licences.py")
        if committed != fresh:
            old = {(c["group"], c["name"], c["version"]) for c in json.loads(committed)["components"]}
            new = {(c["group"], c["name"], c["version"]) for c in json.loads(fresh)["components"]}
            for c in sorted(new - old):
                print("  added:   " + " ".join(c))
            for c in sorted(old - new):
                print("  removed: " + " ".join(c))
            die("native/licences/licences.json is stale: run python3 scripts/licences.py "
                "and commit the result")
        doc = json.loads(fresh)
        print("licence inventory current: %d components, %d texts"
              % (len(doc["components"]), len(doc["texts"])))
        return
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(fresh)
    doc = json.loads(fresh)
    print("wrote %s: %d components, %d texts, %d bytes"
          % (os.path.relpath(OUT, ROOT), len(doc["components"]), len(doc["texts"]), len(fresh)))


if __name__ == "__main__":
    main()

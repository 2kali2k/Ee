#!/usr/bin/env python3
"""Scaffold consistency checker for the Ee project.

Runs without any JVM/Gradle:
  1. Kotlin syntax (tree-sitter-kotlin, if installed)
  2. XML well-formedness
  3. libs.versions.toml parsing
  4. Gradle KTS bracket balance + alias/project-reference resolution
  5. settings.gradle.kts <-> module build files
  6. namespace <-> source directory layout
  7. AndroidManifest class references

Exit code 0 = all good, 1 = errors found.
"""
import os
import re
import sys
import tomllib
import xml.etree.ElementTree as ET

root = os.environ.get("EE_ROOT", os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
errors, warnings = [], []

SKIP = ("/.git", "/.apk-analysis", "/build", "/.gradle", "/.idea")


def walk_all(suffix):
    out = []
    for dp, dn, fn in os.walk(root):
        if any(x in dp for x in SKIP):
            continue
        for f in fn:
            if f.endswith(suffix):
                out.append(os.path.join(dp, f))
    return out


# ---------- 1. Kotlin syntax ----------
try:
    import tree_sitter
    from tree_sitter_kotlin import language as kt_lang

    parser = tree_sitter.Parser(tree_sitter.Language(kt_lang()))

    def parse_kt(path):
        tree = parser.parse(open(path, "rb").read())
        bad = []

        def walk(node):
            if node.type == "ERROR" or node.is_missing:
                bad.append(node.start_point[0] + 1)
            for c in node.children:
                walk(c)

        walk(tree.root_node)
        return bad

    kt_files = walk_all(".kt")
    kt_fail = 0
    for p in kt_files:
        bad = parse_kt(p)
        if bad:
            kt_fail += 1
            errors.append(f"KOTLIN {os.path.relpath(p, root)}: error/missing nodes at lines {sorted(set(bad))[:10]}")
    print(f"[kotlin] {len(kt_files)} files, {kt_fail} with syntax errors")
except Exception as e:  # noqa: BLE001
    warnings.append(f"tree-sitter kotlin unavailable: {e}")

# ---------- 2. XML ----------
for p in walk_all(".xml"):
    try:
        ET.parse(p)
    except Exception as e:  # noqa: BLE001
        errors.append(f"XML {os.path.relpath(p, root)}: {e}")
print(f"[xml] {len(walk_all('.xml'))} files parsed")

# ---------- 3. TOML ----------
toml = tomllib.load(open(os.path.join(root, "gradle/libs.versions.toml"), "rb"))
lib_aliases = set(toml.get("libraries", {}))
plugin_aliases = set(toml.get("plugins", {}))
print(f"[toml] {len(lib_aliases)} libraries, {len(plugin_aliases)} plugins")

# ---------- 4. Gradle KTS ----------
gradle_files = walk_all(".gradle.kts")
used_libs, used_plugins, project_refs, all_ns = set(), set(), set(), {}
for p in gradle_files:
    src = open(p, encoding="utf-8").read()
    rel = os.path.relpath(p, root)
    for a, b in (("{", "}"), ("(", ")")):
        if src.count(a) != src.count(b):
            errors.append(f"GRADLE {rel}: unbalanced {a}{b}")
    for m in re.finditer(r"\blibs\.([a-zA-Z][\w.-]*)", src):
        alias = m.group(1)
        (used_plugins if alias.startswith("plugins.") else used_libs).add(
            alias[len("plugins."):] if alias.startswith("plugins.") else alias)
    project_refs.update(re.findall(r'project\("(:[^"]+)"\)', src))
    m = re.search(r'namespace\s*=\s*"([^"]+)"', src)
    if m:
        all_ns[rel] = m.group(1)
    m2 = re.search(r'applicationId\s*=\s*"([^"]+)"', src)
    if m2:
        all_ns[rel + " (appId)"] = m2.group(1)

for a in sorted(used_libs):
    if a.replace(".", "-") not in lib_aliases:
        errors.append(f"GRADLE alias 'libs.{a}' missing from libs.versions.toml")
for a in sorted(used_plugins):
    if a.replace(".", "-") not in plugin_aliases:
        errors.append(f"GRADLE plugin alias 'libs.plugins.{a}' missing from libs.versions.toml")

# ---------- 5. settings <-> modules ----------
settings = open(os.path.join(root, "settings.gradle.kts"), encoding="utf-8").read()
included = set(re.findall(r'include\("(:[^"]+)"\)', settings))
for mod in sorted(included):
    if not os.path.exists(os.path.join(root, mod.lstrip(":").replace(":", "/"), "build.gradle.kts")):
        errors.append(f"settings includes '{mod}' but build.gradle.kts missing")
for ref in sorted(project_refs):
    if ref not in included:
        errors.append(f"project ref '{ref}' not in settings.gradle.kts")
print(f"[settings] {len(included)} modules")

# ---------- 6. namespace layout ----------
for rel, ns in sorted(all_ns.items()):
    if " (appId)" in rel:
        continue
    expect = ns.replace(".", "/")
    base = os.path.join(root, os.path.dirname(rel))
    if not os.path.exists(os.path.join(base, "src/main/kotlin/" + expect)):
        warnings.append(f"namespace {ns} ({rel}): no src/main/kotlin/{expect}")

# ---------- 7. manifest classes ----------
am = open(os.path.join(root, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
for cls in re.findall(r'android:name="\.([A-Za-z0-9]+)"', am):
    if not os.path.exists(os.path.join(root, f"app/src/main/kotlin/app/ee/{cls}.kt")):
        errors.append(f"manifest .{cls} but app/src/main/kotlin/app/ee/{cls}.kt missing")

print(f"[gradle] {len(gradle_files)} build files; {len(used_libs)} lib + {len(used_plugins)} plugin aliases")

print()
if warnings:
    print("WARNINGS:")
    for w in warnings:
        print("  !", w)
if errors:
    print("ERRORS:")
    for e in errors:
        print("  ✗", e)
    sys.exit(1)
print("ALL CHECKS PASSED ✔")

#!/usr/bin/env python3
"""SDK-free structural checks only, not a substitute for aapt/lint/device testing."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
main = root / "app/src/main"
resources: set[tuple[str, str]] = set()
documents = []
for file in main.rglob("*.xml"):
    tree = ET.parse(file)
    documents.append(file)
    if file.parent.name.startswith("values"):
        for item in tree.getroot():
            if "name" in item.attrib:
                resources.add((item.tag, item.attrib["name"]))
    elif file != main / "AndroidManifest.xml":
        resources.add((file.parent.name.split("-")[0], file.stem))
    for item in tree.iter():
        for value in item.attrib.values():
            if value.startswith("@+id/"):
                resources.add(("id", value.split("/", 1)[1]))

errors = []
for file in [*documents, *main.rglob("*.kt")]:
    content = file.read_text()
    pattern = r"(?<!android\.)R\.(\w+)\.(\w+)" if file.suffix == ".kt" else r"@(?!(?:android:|\+))(\w+)/([\w.]+)"
    for kind, name in re.findall(pattern, content):
        if (kind, name) not in resources:
            errors.append(f"{file.relative_to(root)}: missing {kind}/{name}")

manifest = ET.parse(main / "AndroidManifest.xml").getroot()
ns = "{http://schemas.android.com/apk/res/android}"
application = manifest.find("application")
assert application is not None
assert application.get(ns + "usesCleartextTraffic") == "false"
assert application.get(ns + "allowBackup") == "false"
for item in application:
    if item.tag in {"activity", "service", "receiver"}:
        name = item.get(ns + "name", "").removeprefix(".")
        source = main / "java/io/github/brunofin/caldavtasks" / (name.replace(".", "/") + ".kt")
        # WidgetActionActivity deliberately shares its widget's source file.
        if name != "widget.WidgetActionActivity" and not source.exists():
            errors.append(f"Manifest component has no source: {name}")
assert not errors, "\n".join(errors)
print(f"PASS {len(documents)} XML documents, local resource references, manifest sources and security flags")

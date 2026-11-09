#!/usr/bin/env python3
"""Local static resource validation. No network, artifact upload, or build invocation."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

module = Path(__file__).resolve().parents[1]
root = module.parents[1]
resources = ET.parse(module / "src/commonMain/composeResources/values/strings.xml").getroot()
values = {entry.attrib["name"]: entry.text or "" for entry in resources}
assert len(values) == len(resources), "Duplicate resource names"
english = {name[:-3] for name in values if name.endswith("_en")}
arabic = {name[:-3] for name in values if name.endswith("_ar")}
assert english == arabic, f"Missing translation: {english ^ arabic}"
placeholders = lambda text: sorted(re.findall(r"%[0-9]+\$[sd]", text))
for name in english:
    assert values[name + "_en"].strip() and values[name + "_ar"].strip(), name
    assert placeholders(values[name + "_en"]) == placeholders(values[name + "_ar"]), name
bindings = (module / "src/commonMain/kotlin/com/ukkera/pos/app/ui/Copy.kt").read_text()
bound = set(re.findall(r"Res\.string\.([a-z0-9_]+)", bindings))
assert bound == set(values), f"Binding mismatch: {bound ^ set(values)}"
for relative in ["apps/android/src/main/AndroidManifest.xml", "apps/android/src/main/res/values/strings.xml",
                 "apps/android/src/main/res/values-ar/strings.xml", "apps/ios/Info.plist",
                 "apps/ios/UkkeraPos.xcodeproj/xcshareddata/xcschemes/UkkeraPos.xcscheme"]:
    ET.parse(root / relative)
print(f"PASS: {len(english)} Arabic/English Compose resource pairs, placeholders and bindings; 5 native XML files")

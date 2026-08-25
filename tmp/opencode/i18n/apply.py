#!/usr/bin/env python3
"""Harness: diff default vs locale strings.xml, apply translation dict, append before </resources>.
Usage: apply.py <locale> <transfile.py>
transfile.py must define T = {name: translated_value}
"""
import re, sys, io

RES = "core/resources/src/main/res"
DEF = f"{RES}/values/strings.xml"

def parse(path):
    """Return dict name -> full <string ...>...</string> line content pieces."""
    src = open(path, encoding="utf-8").read()
    out = {}
    # match string elements incl multiline values? values are single-line here except change_lang_warn.
    pat = re.compile(r'<string\s+name="([^"]+)"([^>]*)>(.*?)</string>', re.S)
    for m in pat.finditer(src):
        out[m.group(1)] = m.group(2), m.group(3)
    plur = re.compile(r'(<plurals\s+name="([^"]+)".*?</plurals>)', re.S)
    for m in plur.finditer(src):
        out["plur:" + m.group(2)] = None, m.group(1)
    return out

def esc_check(name, en, tr):
    """Verify placeholders preserved."""
    ph = re.compile(r'%\d+\$[sd]|%[sd]|%d%%')
    e, t = sorted(ph.findall(en) if isinstance(en, str) else []), sorted(ph.findall(tr) if isinstance(tr, str) else [])
    assert e == t, f"placeholder mismatch {name}: {e} vs {t}"

locale, tf = sys.argv[1], sys.argv[2]
ns = {"T": {}, "P": {}}
exec(open(tf, encoding="utf-8").read(), ns)
T, P = ns["T"], ns["P"]

defs = parse(DEF)
locf = f"{RES}/values-{locale}/strings.xml"
src = open(locf, encoding="utf-8").read()
existing = set(re.findall(r'name="([^"]+)"', src))

missing = [k for k in defs if k not in existing and not k.startswith("plur:")
           and 'translatable="false"' not in defs[k][0]]
missing_plur = [k for k in defs if k.startswith("plur:") and k[5:] not in existing]
for k in missing_plur:
    assert k[5:] in P, f"missing plural translations {k} for {locale}"

untranslated = [k for k in missing if k not in T]
assert not untranslated, f"MISSING TRANSLATIONS for {locale}: {untranslated}"

lines = ["\n"]
for k in missing:
    en = defs[k][1]
    esc_check(k, en, T[k])
    lines.append(f'    <string name="{k}">{T[k]}</string>\n')
import xml.etree.ElementTree as ET
for k in missing_plur:
    items = "\n".join(
        f'        <item quantity="{q}">{v}</item>' for q, v in P[k[5:]].items()
    )
    body = f'    <plurals name="{k[5:]}">\n{items}\n    </plurals>\n'
    ET.fromstring(body)  # validate
    lines.append(body + "\n")

new = src.rstrip()
assert new.endswith("</resources>")
new = new[: -len("</resources>")].rstrip("\n") + "\n" + "".join(lines).rstrip("\n") + "\n</resources>\n"

# validate xml
import xml.etree.ElementTree as ET
ET.fromstring(new)
open(locf, "w", encoding="utf-8").write(new)
print(f"{locale}: added {len(missing)} strings + {len(missing_plur)} plurals")

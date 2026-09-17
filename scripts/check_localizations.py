#!/usr/bin/env python3
"""Validate the catalogue, translation review state, native outputs and UI coverage."""
import json
import plistlib
import re
import sys
import xml.etree.ElementTree as ET
from localization_catalog import ROOT, load, validate
from localization_audit import GENERATED, check as check_display_text
from generate_localizations import outputs


def main():
    errors, reviewed_literals, source_count = check_display_text(ROOT)
    data = load()
    errors.extend(validate(data))
    if errors:
        print('\n'.join(errors), file=sys.stderr); return 1
    catalog, plurals = data['catalog'], data['plurals']
    for locale in data['locales']['locales']:
        for name in ('localized_strings.xml', 'localized_plurals.xml', 'strings.xml'):
            path = ROOT / 'android/app/src/main/res' / locale['androidFolder'] / name
            if not path.exists(): errors.append(f'Missing native resource: {path.relative_to(ROOT)}'); continue
            root = ET.parse(path).getroot()
            if len(root) != len({child.attrib['name'] for child in root}): errors.append(f'Duplicate native resource: {path}')
        path = ROOT / 'ios/TacticalMaps/Resources' / (locale['iosFolder'] + '.lproj') / 'Localizable.stringsdict'
        if not path.exists(): errors.append(f'Missing native plurals: {path.relative_to(ROOT)}'); continue
        native = plistlib.loads(path.read_bytes())
        if set(native) != {'count.' + noun for noun in plurals}: errors.append(f'iOS plural parity failure: {locale["tag"]}')
    for platform, folder, suffix in [('ios', 'ios/TacticalMaps', 'swift'), ('android', 'android/app/src/main/java', 'kt')]:
        keys = {entry['en'] for entry in catalog.values() if platform in entry['platforms'] and entry.get('legacy', True)}
        for path in (ROOT / folder).rglob('*.' + suffix):
            if path.relative_to(ROOT).as_posix() in GENERATED or path.name in ('L10n.swift', 'L10n.kt'): continue
            for match in re.finditer(r'L10n\.text\(("(?:\\.|[^"\\])*")', path.read_text()):
                text = json.loads(match[1].replace('\\$', '$'))
                text = re.sub(r'%(\d+)\$[@s]', lambda m: '{' + m[1] + '}', text)
                if text not in keys: errors.append(f'Untranslated legacy lookup in {path.relative_to(ROOT)}: {text}')
            for noun in re.findall(r'L10n\.quantity\("([^"]+)"', path.read_text()):
                if noun not in plurals: errors.append(f'Unknown plural key: {noun}')
    for path, expected in outputs(data=data).items():
        if not path.exists() or path.read_text() != expected: errors.append(f'Stale generated output: {path.relative_to(ROOT)}')
    project = (ROOT / 'ios/project.yml').read_text()
    if not re.search(r'^\s*- Localizations\.yml\s*$', project, re.M): errors.append('XcodeGen must include generated Localizations.yml')
    if 'CFBundleLocalizations:' in project: errors.append('CFBundleLocalizations must come from the locale manifest include')
    if errors:
        print('\n'.join(errors), file=sys.stderr); return 1
    print(f'Display guard: {source_count} source components, {reviewed_literals} reviewed literal occurrences.')
    print(f'Validated {len(catalog)} messages, {len(plurals)} plural families, translation review fingerprints and all native outputs.')
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Validate translation coverage, native resource parity and format safety."""
from pathlib import Path
import importlib.util
import json
import plistlib
import re
import sys
import xml.etree.ElementTree as ET
from localization_audit import check as check_display_text

ROOT = Path(__file__).resolve().parents[1]


def main():
    errors, reviewed_literals, source_count = check_display_text(ROOT)
    catalog = json.loads((ROOT / 'localization/catalog.json').read_text())
    seen = set()
    for key, entry in catalog.items():
        if not re.fullmatch(r'[a-z][a-z0-9_]*', key):
            errors.append(f'Invalid Android identifier: {key}')
        if entry['en'] in seen:
            errors.append(f'Duplicate English key: {entry["en"]}')
        seen.add(entry['en'])
        if not entry['de'].strip():
            errors.append(f'Missing German translation: {key}')
        expected = set(re.findall(r'\{\d+\}', entry['en']))
        actual = set(re.findall(r'\{\d+\}', entry['de']))
        if expected != actual:
            errors.append(f'Placeholder mismatch: {key}: {expected} != {actual}')
        if '%' in entry['en'] or '%' in entry['de']:
            errors.append(f'Use placeholders rather than printf directives in catalog: {key}')
    for name in ('localized_strings.xml', 'localized_plurals.xml', 'strings.xml'):
        roots = [ET.parse(ROOT / f'android/app/src/main/res/{folder}/{name}').getroot()
                 for folder in ('values', 'values-de')]
        names = [{child.attrib['name'] for child in root} for root in roots]
        if names[0] != names[1]:
            errors.append(f'Android resource parity failure: {name}')
        for root in roots:
            if len(root) != len({child.attrib['name'] for child in root}):
                errors.append(f'Duplicate Android resources in {name}')
    plurals = json.loads((ROOT / 'localization/plurals.json').read_text())
    for lang in ('en', 'de'):
        path = ROOT / f'ios/TacticalMaps/Resources/{lang}.lproj/Localizable.stringsdict'
        native = plistlib.loads(path.read_bytes())
        if set(native) != {'count.' + noun for noun in plurals}:
            errors.append(f'iOS plural parity failure: {lang}')
        for noun, translations in plurals.items():
            one, other = translations[lang]
            if one.count('%d') != 1 or other.count('%d') != 1:
                errors.append(f'Plural count mismatch: {noun}/{lang}')
            for quantity, value in zip(('one', 'other'), (one, other)):
                if native['count.' + noun]['count'][quantity] != value:
                    errors.append(f'Stale plural: {noun}/{lang}/{quantity}')
    # Whole literal lookups must be backed by a reviewed native resource. Enum
    # display keys are additionally covered by the platform resource tests.
    for platform, folder, suffix in [('ios', 'ios/TacticalMaps', 'swift'),
                                     ('android', 'android/app/src/main/java', 'kt')]:
        keys = {entry['en'] for entry in catalog.values() if platform in entry['platforms']}
        for path in (ROOT / folder).rglob('*.' + suffix):
            if path.name in ('LocalizedStringIds.kt', 'L10n.swift', 'L10n.kt'):
                continue
            for match in re.finditer(r'L10n\.text\(("(?:\\.|[^"\\])*")', path.read_text()):
                quoted = match[1].replace('\\$', '$')
                text = json.loads(quoted)
                text = re.sub(r'%(\d+)\$[@s]', lambda m: '{' + m[1] + '}', text)
                if text not in keys:
                    errors.append(f'Untranslated lookup in {path.relative_to(ROOT)}: {text}')
            for noun in re.findall(r'L10n\.quantity\("([^"]+)"', path.read_text()):
                if noun not in plurals:
                    errors.append(f'Unknown plural key: {noun}')
    spec = importlib.util.spec_from_file_location('generate_localizations', ROOT / 'scripts/generate_localizations.py')
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    for path, expected in generator.outputs().items():
        if not path.exists() or path.read_text() != expected:
            errors.append(f'Stale generated resource: {path.relative_to(ROOT)}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'Display guard: {source_count} registered source components, {reviewed_literals} reviewed literal occurrences.')
    print(f'Validated {len(catalog)} translations, {len(plurals)} plural families, native resource parity and literal lookup coverage.')
    return 0


if __name__ == '__main__':
    sys.exit(main())

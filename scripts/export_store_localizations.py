#!/usr/bin/env python3
"""Validate and export paste-ready store metadata. Does not publish anything."""
import json
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parents[1]
LIMITS = {
    'appStore': {'name': 30, 'subtitle': 30, 'promotionalText': 170, 'description': 4000, 'whatsNew': 4000},
    'googlePlay': {'title': 30, 'shortDescription': 80, 'description': 4000, 'releaseNotes': 500},
    'purchase': {'name': 30, 'description': 45},
}

def export(destination=None):
    for path in sorted((ROOT / 'docs/store/localizations').glob('*.json')):
        data = json.loads(path.read_text())
        play = data['googlePlay']
        if play.get('descriptionFromAppStore'):
            description = data['appStore']['description']
            for old, new in play['descriptionReplacements'].items():
                assert old in description, (path, old)
                description = description.replace(old, new)
            play['description'] = description
        assert len(data['appStore']['keywords'].encode('utf-8')) <= 100, path
        for section, fields in LIMITS.items():
            for name, maximum in fields.items():
                value = data[section][name]
                assert 0 < len(value) <= maximum, (path, section, name, len(value), maximum)
                if destination:
                    folder = destination / path.stem / section
                    folder.mkdir(parents=True, exist_ok=True)
                    (folder / (name + '.txt')).write_text(value + '\n')
        if destination:
            folder = destination / path.stem / 'appStore'
            (folder / 'keywords.txt').write_text(data['appStore']['keywords'] + '\n')
        print(f'{path.stem}: listing, keywords, release notes and purchase fields pass limits')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    export(parser.parse_args().output)

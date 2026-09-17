#!/usr/bin/env python3
"""Record an explicit linguistic review for selected messages, never bulk-approve.

Run only after reviewing the current English/context and target-language wording.
This records review provenance; it cannot itself assess translation quality.
"""
import argparse
import json
from localization_catalog import ROOT, load, review_record, source_value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=('catalog', 'plurals', 'permissions'), default='catalog')
    parser.add_argument('--locale', required=True)
    parser.add_argument('--id', action='append', required=True, dest='ids')
    options = parser.parse_args()
    data = load()
    tags = {locale['tag'] for locale in data['locales']['locales']} - {'en'}
    if options.locale not in tags: parser.error('Choose an enabled translation locale (not source English)')
    for key in options.ids:
        if key not in data[options.kind] or options.locale not in data[options.kind][key]: parser.error('Missing message/translation: ' + key)
    for key in options.ids:
        entry = data[options.kind][key]
        source = source_value(entry) if options.kind == 'catalog' else entry['en']
        data['reviews'][options.kind].setdefault(key, {})[options.locale] = review_record(source, entry[options.locale], 'reviewed')
    (ROOT / 'localization/reviews.json').write_text(json.dumps(data['reviews'], ensure_ascii=False, indent=2) + '\n')
    print(f'Recorded review for {len(options.ids)} selected {options.locale} entries; regenerate native outputs.')


if __name__ == '__main__':
    main()

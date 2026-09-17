#!/usr/bin/env python3
"""Render the measured source inventory; counts are not proof of UI completeness."""
from collections import Counter
import json
from localization_audit import ROOT, Lexer, audit, source_files


def report(root=ROOT):
    inventory = json.loads((root / 'localization/source-inventory.json').read_text())
    catalog = json.loads((root / 'localization/catalog.json').read_text())
    findings, failures = audit(root)
    if failures: raise ValueError('\n'.join(failures))
    exceptions = Counter(f['path'] for f in findings)
    rows = {e['path']: e for e in inventory}
    counts = Counter((entry['area'], entry['platform']) for entry in inventory)
    lines = ['# TacMap localisation coverage inventory', '',
             f'{len(inventory)} registered source components; {len(catalog)} catalogue entries.', '',
             'This report measures source coverage and records the remaining review work. '
             'A registered component or successful scan is not a completed language/device review. '
             'Dynamic lookups are often intentional enum keys; their producers still require review.', '',
             '## Areas', '', '| Area | iOS components | Android components |', '| --- | ---: | ---: |']
    for area in sorted({entry['area'] for entry in inventory}):
        lines.append(f'| {area} | {counts[area, "ios"]} | {counts[area, "android"]} |')
    lines += ['', '## Source components', '',
              '| Platform | Area | Component | Localisation calls | Dynamic lookups | Literal exceptions | Review status |',
              '| --- | --- | --- | ---: | ---: | ---: | --- |']
    for platform, relative, path in source_files(root):
        tokens = Lexer(path.read_text(), platform).tokens()
        lookups = [i for i in range(len(tokens) - 4)
                   if tokens[i].value == 'L10n' and tokens[i + 1].value == '.'
                   and tokens[i + 2].value in ('text', 'quantity') and tokens[i + 3].value == '(']
        dynamic = sum(tokens[i + 4].kind != 'string' for i in lookups)
        entry = rows[relative]
        lines.append(f'| {platform} | {entry["area"]} | `{relative}` | {len(lookups)} | {dynamic} | {exceptions[relative]} | {entry["reviewStatus"]} |')
    lines += ['', '## Additional surfaces requiring review', '',
              '| Surface | Current evidence | Remaining work |', '| --- | --- | --- |',
              '| Native permission text and locale declarations | English/German resources exist | Test first-run prompts and system/app language combinations |',
              '| Notifications and background services | Producers included in source inventory | Device tests for recording, sync and notification channel text |',
              '| Accessibility | Direct labels/hints/values scanned | VoiceOver/TalkBack and large-text review across the screen inventory |',
              '| App Store / Google Play listings and screenshots | Separate from app catalogue | Prepare and review translated metadata and screenshots |',
              '| Store-managed products and checkout | Platform-controlled text | Review localised product metadata and native purchase flows |',
              '| Help, support and linked policy pages | External surfaces | Inventory and review content independently |', '',
              '## Boundaries', '',
              'The guard recognises common SwiftUI/Compose calls, registered positional UI helpers, '
              'named display arguments, direct display properties and eagerly translated Kotlin enum arguments. '
              'Comments, raw/multiline strings and interpolation are tokenised. '
              'It does not perform arbitrary interprocedural data-flow analysis, inspect image pixels, '
              'infer all custom helper semantics or certify wording/layout quality. '
              'New source files must enter the inventory; new UI helper patterns must extend the guard and its tests.', '',
              'Language-neutral exceptions are exact source/literal/sink matches with occurrence limits and reasons. '
              'Changes in path, wording or occurrence count require review. '
              'N/PIN and similar English/German exceptions must be reconsidered before shipping additional languages.', '']
    return '\n'.join(lines)


if __name__ == '__main__':
    print(report(), end='')

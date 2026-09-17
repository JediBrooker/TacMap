"""Shared catalogue schema and review fingerprints. No SDK or third-party packages."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CATEGORIES = ('zero', 'one', 'two', 'few', 'many', 'other')


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def source_value(entry):
    return {key: entry[key] for key in ('en', 'context', 'parameters') if key in entry}


def review_record(source, translation, status):
    return {'source': fingerprint(source), 'translation': fingerprint(translation), 'status': status}


def load(root=ROOT):
    folder = root / 'localization'
    return {name: json.loads((folder / (name + '.json')).read_text())
            for name in ('catalog', 'plurals', 'locales', 'permissions', 'reviews')}


def placeholders(text):
    return Counter(re.findall(r'\{([1-9]\d*)\}', text))


def validate(data):
    errors = []
    manifest = data['locales']
    locales = manifest['locales']
    tags = [locale['tag'] for locale in locales]
    if manifest.get('sourceLocale') != 'en': errors.append('The source language must remain English for the legacy bridge')
    if 'en' not in tags or len(tags) != len(set(tags)): errors.append('Locales need unique tags including en')
    for field in ('iosFolder', 'androidFolder', 'swiftCase', 'kotlinCase'):
        values = [locale[field] for locale in locales]
        if len(values) != len(set(values)): errors.append(f'Duplicate locale {field}')
    for locale in locales:
        tag = locale['tag']
        if not re.fullmatch(r'[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*', tag): errors.append(f'Invalid locale tag: {tag}')
        for field in ('iosFolder', 'androidFolder'):
            if not re.fullmatch(r'[A-Za-z0-9+_-]+', locale[field]): errors.append(f'Unsafe {field}: {tag}')
        if locale['androidFolder'] != 'values' and not locale['androidFolder'].startswith('values-'):
            errors.append(f'Invalid Android resource folder: {tag}')
        if not re.fullmatch('[a-z][A-Za-z0-9]*', locale['swiftCase']) or locale['swiftCase'] == 'system': errors.append(f'Invalid Swift case: {tag}')
        if not re.fullmatch('[A-Z][A-Z0-9_]*', locale['kotlinCase']) or locale['kotlinCase'] == 'SYSTEM': errors.append(f'Invalid Kotlin case: {tag}')
        categories = locale['pluralCategories']
        if 'other' not in categories or len(categories) != len(set(categories)) or set(categories) - set(CATEGORIES):
            errors.append(f'Invalid plural categories: {tag}')
        if not locale.get('nativeName', '').strip(): errors.append(f'Missing language autonym: {tag}')
    source_locale = next((locale for locale in locales if locale['tag'] == 'en'), None)
    if source_locale and (source_locale['androidFolder'] != 'values' or set(source_locale['pluralCategories']) != {'one', 'other'}):
        errors.append('English needs default Android resources and one/other plural categories')
    choice = data['catalog'].get(manifest.get('systemChoiceMessage'), {})
    if not choice.get('accessor') or choice.get('parameters') or set(choice.get('platforms', [])) != {'ios', 'android'}:
        errors.append('The system-language label needs a parameterless accessor on both platforms')
    plural_ids = [key.replace('-', '_') for key in data['plurals']]
    if len(plural_ids) != len(set(plural_ids)): errors.append('Plural IDs collide after native name conversion')
    aliases, accessors = {}, set()
    for key, entry in data['catalog'].items():
        if not re.fullmatch('[a-z][a-z0-9_]*', key): errors.append(f'Invalid stable message ID: {key}')
        platforms = entry.get('platforms', [])
        if not platforms or len(platforms) != len(set(platforms)) or set(platforms) - {'ios', 'android'}: errors.append(f'Invalid platforms: {key}')
        # Equal English copy is valid when message contexts differ. Legacy aliases
        # must have a single explicit owner to avoid silently choosing a meaning.
        if entry.get('legacy', True):
            if entry['en'] in aliases: errors.append(f'Ambiguous legacy English alias: {key} / {aliases[entry["en"]]}')
            aliases[entry['en']] = key
        if entry.get('legacy', True) and entry['en'].startswith('id.'):
            errors.append(f'Legacy text collides with stable-ID namespace: {key}')
        expected = placeholders(entry['en'])
        if expected and set(expected) != {str(i) for i in range(1, max(map(int, expected)) + 1)}:
            errors.append(f'Non-contiguous placeholders: {key}')
        for tag in tags:
            text = entry.get(tag)
            if not isinstance(text, str) or not text.strip(): errors.append(f'Missing {tag} translation: {key}'); continue
            if placeholders(text) != expected: errors.append(f'Placeholder mismatch: {key}/{tag}')
            if re.search(r'%(?:\d+\$)?[@sdf]', text): errors.append(f'Use catalogue placeholders instead of printf syntax: {key}/{tag}')
        if 'accessor' in entry:
            accessor = entry['accessor']
            if not re.fullmatch('[a-z][A-Za-z0-9]*', accessor) or accessor in accessors: errors.append(f'Invalid/duplicate accessor: {key}')
            accessors.add(accessor)
            parameters = entry.get('parameters', [])
            if len(parameters) != len(expected): errors.append(f'Parameter count mismatch: {key}')
            if len({p['name'] for p in parameters}) != len(parameters): errors.append(f'Duplicate parameter names: {key}')
            for param in parameters:
                if param['type'] != 'string' or not re.fullmatch('[a-z][A-Za-z0-9]*', param['name']):
                    errors.append(f'Invalid typed parameter: {key}; display arguments are strings, plural counts are integers')
            if not entry.get('context', '').strip(): errors.append(f'Typed message needs translator context: {key}')
    for noun, translations in data['plurals'].items():
        if not re.fullmatch('[a-z][a-z0-9_-]*', noun): errors.append(f'Invalid plural ID: {noun}')
        for locale in locales:
            tag = locale['tag']; forms = translations.get(tag, {})
            if not isinstance(forms, dict) or set(forms) != set(locale['pluralCategories']):
                errors.append(f'Plural categories mismatch: {noun}/{tag}'); continue
            for category, text in forms.items():
                if not isinstance(text, str) or text.count('%d') != 1 or re.search(r'%(?!d)', text):
                    errors.append(f'Plural must contain one integer count: {noun}/{tag}/{category}')
    for key, translations in data['permissions'].items():
        for tag in tags:
            if not translations.get(tag, '').strip(): errors.append(f'Missing permission translation: {key}/{tag}')
    for kind in ('catalog', 'plurals', 'permissions'):
        entries = data[kind]; reviews = data['reviews'].get(kind, {})
        if set(entries) != set(reviews): errors.append(f'Review inventory differs from {kind}')
        for key, entry in entries.items():
            source = source_value(entry) if kind == 'catalog' else entry['en']
            if set(reviews.get(key, {})) != set(tags) - {'en'}: errors.append(f'Review languages differ: {kind}/{key}')
            for tag in tags:
                if tag == 'en': continue
                review = reviews.get(key, {}).get(tag, {})
                if review.get('status') not in ('baseline', 'reviewed'):
                    errors.append(f'Translation needs review: {kind}/{key}/{tag}')
                if review.get('source') != fingerprint(source) or review.get('translation') != fingerprint(entry.get(tag)):
                    errors.append(f'Stale translation review: {kind}/{key}/{tag}')
    return errors

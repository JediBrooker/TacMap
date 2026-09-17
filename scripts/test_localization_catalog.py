"""Schema, migration-equivalence and future-locale tests; no mobile SDK needed."""
from copy import deepcopy
import json
from pathlib import Path
import plistlib
import re
import unittest
import xml.etree.ElementTree as ET
from localization_catalog import ROOT, fingerprint, load, review_record, source_value, validate
from generate_localizations import native, outputs


def reviewed(data, kind, key, tag):
    entry = data[kind][key]
    source = source_value(entry) if kind == 'catalog' else entry['en']
    data['reviews'].setdefault(kind, {}).setdefault(key, {})[tag] = review_record(source, entry[tag], 'reviewed')


def fixture():
    original = load()
    data = deepcopy(original)
    data['catalog'] = {k: e for k, e in data['catalog'].items() if 'accessor' in e}
    data['plurals'] = {'point': data['plurals']['point']}
    data['reviews'] = {kind: {key: original['reviews'][kind][key] for key in data[kind]}
                       for kind in ('catalog', 'plurals', 'permissions')}
    return data


class CatalogueTests(unittest.TestCase):
    def test_current_schema_and_review_records(self):
        self.assertEqual(validate(load()), [])

    def test_source_edit_requires_review(self):
        data = fixture()
        data['catalog']['settings_language_title']['en'] = 'Display language'
        self.assertTrue(any('Stale translation review' in e for e in validate(data)))
        with self.assertRaises(ValueError): outputs(data=data)
        reviewed(data, 'catalog', 'settings_language_title', 'de')
        self.assertEqual(validate(data), [])
        generated = outputs(data=data)
        self.assertIn('"id.settings_language_title" = "Display language";', generated[ROOT / 'ios/TacticalMaps/Resources/en.lproj/Localizable.strings'])
        self.assertIn('static func settingsLanguageTitle()', generated[ROOT / 'ios/TacticalMaps/Util/Messages.swift'])

    def test_translation_or_context_edits_require_review(self):
        for field in ['de', 'context']:
            data = fixture()
            data['catalog']['settings_language_title'][field] += ' changed'
            self.assertTrue(any('Stale translation review' in e for e in validate(data)))

    def test_placeholder_loss_and_wrong_parameter_type_fail(self):
        data = fixture()
        data['catalog']['import_failed']['de'] = 'Import fehlgeschlagen'
        reviewed(data, 'catalog', 'import_failed', 'de')
        self.assertTrue(any('Placeholder mismatch' in e for e in validate(data)))
        data = fixture()
        data['catalog']['import_failed']['parameters'][0]['type'] = 'arbitraryObject'
        self.assertTrue(any('Invalid typed parameter' in e for e in validate(data)))

    def test_generated_accessor_preserves_typed_argument(self):
        generated = outputs(data=fixture())
        self.assertIn('func importFailed(_ detail: String)', generated[ROOT / 'ios/TacticalMaps/Util/Messages.swift'])
        self.assertIn('fun importFailed(detail: String)', generated[ROOT / 'android/app/src/main/java/com/tacmap/localization/Messages.kt'])
        self.assertIn('func pointCount(_ count: Int)', generated[ROOT / 'ios/TacticalMaps/Util/Messages.swift'])

    def test_deferred_accessors_reject_invalid_flags_and_collisions(self):
        data = fixture()
        data['catalog']['common_ok']['deferred'] = 'yes'
        self.assertTrue(any('Deferred message' in e for e in validate(data)))
        data = fixture()
        data['catalog']['common_ok']['deferred'] = True
        data['catalog']['settings_language_title']['accessor'] = 'acknowledgeMessage'
        self.assertTrue(any('collides' in e for e in validate(data)))

    def test_same_english_can_have_distinct_context_ids(self):
        data = fixture()
        entry = deepcopy(data['catalog']['common_ok'])
        entry.update(de='Bestätigen', accessor='confirmAction', context='Confirm an irreversible action.', legacy=False)
        data['catalog']['confirm_action'] = entry
        reviewed(data, 'catalog', 'confirm_action', 'de')
        self.assertEqual(validate(data), [])
        native_text = outputs(data=data)[ROOT / 'ios/TacticalMaps/Resources/de.lproj/Localizable.strings']
        self.assertIn('"id.confirm_action" = "Bestätigen";', native_text)
        self.assertIn('"id.common_ok" = "OK";', native_text)
        entry['legacy'] = True
        self.assertTrue(any('Ambiguous legacy' in e for e in validate(data)))

    def test_third_locale_with_six_plural_forms_needs_no_generator_edits(self):
        data = fixture()
        data['locales']['locales'].append({'tag':'ar', 'nativeName':'العربية', 'iosFolder':'ar', 'androidFolder':'values-ar',
                                          'swiftCase':'ar', 'kotlinCase':'ARABIC', 'pluralCategories':['zero','one','two','few','many','other']})
        for kind in ('catalog', 'permissions'):
            for key, entry in data[kind].items():
                entry['ar'] = entry['en']  # Synthetic fixture, never written to production resources.
                reviewed(data, kind, key, 'ar')
        data['plurals']['point']['ar'] = {category: '%d test ' + category for category in ['zero','one','two','few','many','other']}
        reviewed(data, 'plurals', 'point', 'ar')
        self.assertEqual(validate(data), [])
        generated = outputs(data=data)
        xml = ET.fromstring(generated[ROOT / 'android/app/src/main/res/values-ar/localized_plurals.xml'])
        self.assertEqual({e.attrib['quantity'] for e in xml[0]}, {'zero','one','two','few','many','other'})
        ios = plistlib.loads(generated[ROOT / 'ios/TacticalMaps/Resources/ar.lproj/Localizable.stringsdict'].encode())
        self.assertIn('few', ios['count.point']['count'])
        self.assertIn('case ar = "ar"', generated[ROOT / 'ios/TacticalMaps/Util/SupportedLanguage.swift'])
        self.assertIn('ARABIC("ar", "العربية")', generated[ROOT / 'android/app/src/main/java/com/tacmap/localization/SupportedLanguage.kt'])
        self.assertIn('"ar"', generated[ROOT / 'ios/Localizations.yml'])
        self.assertIn('android:name="ar"', generated[ROOT / 'android/app/src/main/res/xml/locales_config.xml'])
        del data['plurals']['point']['ar']['few']
        self.assertTrue(any('Plural categories mismatch' in e for e in validate(data)))

    def test_missing_translation_or_review_never_silently_enables_locale(self):
        data = fixture()
        del data['catalog']['common_ok']['de']
        self.assertTrue(any('Missing de translation' in e for e in validate(data)))
        data = fixture()
        data['reviews']['catalog']['common_ok']['de']['status'] = 'draft'
        self.assertTrue(any('needs review' in e for e in validate(data)))

    def test_reordered_placeholders_and_literal_percent_are_safe(self):
        self.assertEqual(native('Done {1}: 100%', 'ios'), 'Done %1$@: 100%%')
        self.assertEqual(native('100%', 'android'), '100%')
        self.assertEqual(native('{2}, {1}', 'android'), '%2$s, %1$s')

    def test_unsafe_manifest_paths_and_duplicate_ids_fail(self):
        data = fixture()
        data['locales']['locales'][1]['iosFolder'] = '../escape'
        self.assertTrue(any('Unsafe iosFolder' in e for e in validate(data)))
        data = fixture()
        data['locales']['locales'][1]['kotlinCase'] = 'ENGLISH'
        self.assertTrue(any('Duplicate locale kotlinCase' in e for e in validate(data)))

    def test_existing_language_text_and_plurals_are_unchanged(self):
        data = load(); generated = outputs(data=data)
        golden = json.loads((ROOT / 'testdata/localization/legacy-display-hashes.json').read_text())
        for tag in ('en', 'de'):
            ios = generated[ROOT / f'ios/TacticalMaps/Resources/{tag}.lproj/Localizable.strings']
            pairs = re.findall(r'^("(?:\\.|[^"\\])*") = ("(?:\\.|[^"\\])*");$', ios, re.M)
            legacy = {json.loads(k):json.loads(v) for k,v in pairs if not json.loads(k).startswith('id.')}
            self.assertEqual(fingerprint(legacy), golden['messages']['ios_' + tag])
            folder = 'values' if tag == 'en' else 'values-de'
            xml = ET.fromstring(generated[ROOT / f'android/app/src/main/res/{folder}/localized_strings.xml'])
            strings = {child.attrib['name']: json.loads(child.text.replace("\\'", "'")) for child in xml}
            legacy = {native(entry['en'], 'android'): strings[key] for key,entry in data['catalog'].items() if 'android' in entry['platforms'] and entry.get('legacy',True)}
            self.assertEqual(fingerprint(legacy), golden['messages']['android_' + tag])
            ios_plurals = plistlib.loads(generated[ROOT / f'ios/TacticalMaps/Resources/{tag}.lproj/Localizable.stringsdict'].encode())
            forms = {noun:{category:ios_plurals['count.'+noun]['count'][category] for category in ('one','other')} for noun in data['plurals']}
            self.assertEqual(fingerprint(forms), golden['plurals'][tag])


if __name__ == '__main__':
    unittest.main()

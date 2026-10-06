"""Store copy, privacy policy and threat model claims that have to match the apps.

    python3 -m unittest discover -s scripts -p 'test_store_docs.py'
"""
import json
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import export_store_localizations  # noqa: E402

LOCALES = sorted((ROOT / 'docs/store/localizations').glob('*.json'))
# both apps only take PDF, GeoPDF and MBTiles, so any photo/scan wording has to say saved as PDF
IMAGE_WORDS = re.compile(r'photo|foto|scan|eingescannt', re.I)
SAVED_AS_PDF = re.compile(r'saved as PDF|als PDF gespeichert', re.I)


def text_fields(data):
    for section in ('appStore', 'googlePlay'):
        for key, value in data[section].items():
            if isinstance(value, str):
                yield f'{section}.{key}', value


class StoreCopyTests(unittest.TestCase):
    def test_fields_fit_store_limits(self):
        export_store_localizations.export()

    def test_no_claim_that_images_can_be_imported(self):
        for path in LOCALES:
            data = json.loads(path.read_text())
            for field, value in text_fields(data):
                for line in value.split('\n'):
                    if IMAGE_WORDS.search(line):
                        self.assertRegex(line, SAVED_AS_PDF, f'{path.name} {field}')
        for line in (ROOT / 'README.md').read_text().splitlines():
            if re.search(r'photo', line, re.I):
                self.assertRegex(line, SAVED_AS_PDF)

    def test_release_notes_are_for_3_0_3(self):
        for path in LOCALES:
            data = json.loads(path.read_text())
            self.assertTrue(data['appStore']['whatsNew'].startswith('TacMap 3.0.3: '), path.name)
            self.assertTrue(data['googlePlay']['releaseNotes'].startswith('TacMap 3.0.3\n'), path.name)
            # the notes for the release before go to RELEASE_NOTES.md, not in here
            for field, value in text_fields(data):
                self.assertNotIn('3.0.2', value, f'{path.name} {field}')

    def test_release_notes_history_has_3_0_3(self):
        # RELEASE_NOTES.md carries the text that ships, so it can't drift from the json
        history = (ROOT / 'docs/store/RELEASE_NOTES.md').read_text()
        self.assertIn('## 3.0.3 (build 76)', history)
        for path in LOCALES:
            data = json.loads(path.read_text())
            for text in (data['appStore']['whatsNew'], data['googlePlay']['releaseNotes']):
                self.assertIn(f'```\n{text}\n```', history, path.name)

    def test_play_room_note_is_about_edits_not_visibility(self):
        # 3.0.1 never hid an android-made object from 2.x android, it was the later edits and deletes
        # that stopped arriving once an iOS edit flipped the id casing. so a note about those rooms
        # promises edits, not visibility. 3.0.3 has none, the check stays for the next one that does
        words = {'en-US.json': ('changes', 'visible'), 'de-DE.json': ('Änderungen', 'sichtbar')}
        for name, (must, mustnt) in words.items():
            notes = json.loads((ROOT / 'docs/store/localizations' / name).read_text())['googlePlay']['releaseNotes']
            room = [line for line in notes.split('\n') if '(2:' in line]
            self.assertLessEqual(len(room), 1, name)
            for line in room:
                self.assertIn(must, line, name)
                self.assertNotIn(mustnt, line, name)


class PrivacyPolicyTests(unittest.TestCase):
    def test_offline_tile_bakes_are_disclosed(self):
        # THREAT_MODEL section 7 lists bakes as plaintext at rest, the policy has to as well
        self.assertIn('tacmap-bake-', (ROOT / 'docs/THREAT_MODEL.md').read_text())
        policies = {
            'docs/PRIVACY_POLICY.md': r'Imported map bytes, offline tiles generated\s+from them, and local crash reports\s+are the documented exceptions',
            'docs/de/PRIVACY_POLICY.md': r'Importierte Kartendateien, daraus erstellte Offline-Kacheln und lokale Absturzberichte sind die beschriebenen Ausnahmen',
        }
        for rel, exceptions in policies.items():
            text = (ROOT / rel).read_text()
            rows = [line for line in text.splitlines() if line.startswith('| **') and 'tacmap-bake-' in line]
            self.assertEqual(len(rows), 1, rel)
            self.assertRegex(text, exceptions)


class ThreatModelTests(unittest.TestCase):
    def test_android_import_names_match_the_code(self):
        code = (ROOT / 'android/app/src/main/java/com/tacmap/map/DocumentImportCopy.kt').read_text()
        found = re.search(r'\.take\((\d+)\)\s*\.joinToString\(""\) \{ "%02x"\.format\(it\) \}\s*'
                          r'val finalFile = File\(destinationDir, "import-\$stableName', code)
        self.assertIsNotNone(found, 'DocumentImportCopy naming changed, update this test and THREAT_MODEL')
        digits = int(found.group(1)) * 2
        self.assertIn(f'`import-<{digits} hex>`', (ROOT / 'docs/THREAT_MODEL.md').read_text())


if __name__ == '__main__':
    unittest.main()

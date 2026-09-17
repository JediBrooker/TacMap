"""Behavioural regression fixtures for the source guard; run without mobile SDKs."""
from pathlib import Path
import json
import tempfile
import unittest

from localization_audit import Lexer, check, scan


class DisplayTextAuditTests(unittest.TestCase):
    def literals(self, code, platform='ios'):
        return [f['literal'] for f in scan(code, platform)]

    def test_english_plural_suffix_inside_localized_argument_fails(self):
        self.assertEqual(self.literals('Text(L10n.text("%1$s drawing%2$s", count, if (count == 1) "" else "s"))', 'android'), ['"s"'])
        self.assertEqual(self.literals('Text(L10n.text("%1$@ point%2$@", count, count == 1 ? "" : "s"))'), ['"s"'])
        self.assertEqual(self.literals('Text(Messages.drawingCount(count))', 'android'), [])

    def test_direct_labels_on_both_platforms(self):
        self.assertEqual(self.literals('Text("Save"); Button("Cancel", action: close)'), ['"Save"', '"Cancel"'])
        self.assertEqual(self.literals('Text(text = "Save"); Icon(contentDescription = "Close")', 'android'), ['"Save"', '"Close"'])

    def test_recording_status_computed_property_is_display_copy(self):
        self.assertEqual(self.literals('var statusTitle: String { switch state { case .starting: return "STARTING"; default: return "IDLE" } }'), ['"STARTING"', '"IDLE"'])

    def test_localized_values_and_identifiers_are_not_prose(self):
        source = 'Text(L10n.text("Save")); Text(userName); Image(systemName: "trash"); let key = "wire_value"'
        self.assertEqual(self.literals(source), [])
        self.assertEqual(self.literals('Text(stringResource(R.string.save)); Text(L10n.text("Save"))', 'android'), [])

    def test_only_the_localized_expression_is_exempt(self):
        self.assertEqual(self.literals('Text(L10n.text("Save") + " changes")'), ['" changes"'])
        self.assertEqual(self.literals('Text(if (retry) L10n.text("Retry") else "OK")', 'android'), ['"OK"'])

    def test_nested_comments_do_not_create_findings(self):
        self.assertEqual(self.literals('/* Text("A") /* Button("B") */ */ // Text("C")\nText(name)'), [])

    def test_comments_inside_strings_stay_visible(self):
        self.assertEqual(self.literals('Text("Open /* help */")'), ['"Open /* help */"'])

    def test_multiline_and_raw_strings_are_audited(self):
        self.assertEqual(len(self.literals('Text("""\nLong help\n"""); Text(##"Raw help"##)')), 2)
        self.assertEqual(len(self.literals('Text("""Long $name help""")', 'android')), 1)

    def test_nested_interpolation_and_escape_sequences(self):
        source = r'Text("Hello \(names.joined(separator: ", "))"); Text("Say \"hello\"")'
        self.assertEqual(len(self.literals(source)), 2)
        self.assertEqual(self.literals(r'Text("\(user.name)")'), [])
        self.assertEqual(self.literals('Text("$name"); Text("Hello ${user.name}")', 'android'), ['"Hello ${user.name}"'])

    def test_swift_raw_interpolation_is_not_visible_copy(self):
        self.assertEqual(self.literals(r'Text(#"\#(value)"#)'), [])
        self.assertEqual(self.literals(r'Text(#"Hello \#(value)"#)'), [r'#"Hello \#(value)"#'])

    def test_localized_closure_and_event_payloads_are_separate(self):
        code = 'Button(action: { send("wire_event") }) { Text(L10n.text("Save")) }'
        self.assertEqual(self.literals(code), [])
        self.assertEqual(self.literals('Dialog(title = { Text("Settings") })', 'android'), ['"Settings"'])

    def test_nested_calls_and_named_custom_display_arguments(self):
        self.assertEqual(self.literals('row(title: "Title", icon: "icon-name")'), ['"Title"'])
        self.assertEqual(self.literals('Notification.Builder().setContentText("Recording")', 'android'), ['"Recording"'])
        self.assertEqual(self.literals('Text(String(format: "%.2f", number))'), [])

    def test_stored_and_computed_display_properties(self):
        self.assertEqual(self.literals('let title = "Title"; var errorDescription: String { "Failed" }'), ['"Title"', '"Failed"'])
        self.assertEqual(self.literals('fun receive(message: String) { val key = "wire-key" }', 'android'), [])
        self.assertEqual(self.literals('Button { save() } label: { Image(systemName: "trash") }'), [])

    def test_known_custom_helpers_and_property_assignments(self):
        self.assertEqual(self.literals('Caption("Help"); SettingRow(true, change, "Share")', 'android'), ['"Help"', '"Share"'])
        self.assertEqual(self.literals('label.text = "Updated"; statusMessage = "Failed"'), ['"Updated"', '"Failed"'])

    def test_enum_labels_and_eager_translations(self):
        code = 'enum class Kind(val displayName: String) { @kotlinx.serialization.SerialName("none") NONE("None"), OTHER(L10n.text("Other")); }'
        result = scan(code, 'android')
        self.assertEqual([f['literal'] for f in result], ['"None"', '"Other"'])
        self.assertTrue(result[1]['sink'].startswith('cached.enum.'))
        self.assertEqual(self.literals('enum class Kind(private val key: String) { NONE("None"); val displayName get() = L10n.text(key) }', 'android'), [])

    def test_unicode_and_english_are_both_detected(self):
        self.assertEqual(len(self.literals('Text("Speichern"); Text("保存"); Text("Save")')), 3)
        self.assertEqual(self.literals('Text("—"); Text("100%"); Text("42")'), [])

    def test_bad_source_fails_closed(self):
        for source in ['Text("Missing quote)', 'Text("Title"', '/* unclosed', 'Text("Hello \\(name")']:
            with self.assertRaises(ValueError): scan(source, 'ios')

    def test_exceptions_are_specific_and_counted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / 'ios/TacticalMaps/Example.swift'
            source.parent.mkdir(parents=True)
            source.write_text('Text("TacMap")')
            inventory = root / 'localization'
            inventory.mkdir()
            relative = source.relative_to(root).as_posix()
            finding = scan(source.read_text(), 'ios', relative)[0]
            exception = {k: finding[k] for k in ('id', 'path', 'sink', 'literal')}
            exception.update(reason='Product name', occurrences=1)
            (inventory / 'display-text-exceptions.json').write_text(json.dumps([exception]))
            (inventory / 'source-inventory.json').write_text(json.dumps([{'path': relative, 'platform': 'ios', 'area': 'navigation-settings', 'reviewStatus': 'manual-review-needed'}]))
            self.assertEqual(check(root)[0], [])
            source.write_text('Text("TacMap"); Text("TacMap")')
            self.assertTrue(any('expected 1 occurrences, found 2' in e for e in check(root)[0]))
            source.write_text('Text("TacMap"); Text("Untranslated")')
            self.assertTrue(any('hard-coded display text' in e for e in check(root)[0]))
            source.write_text('Text(name)')
            self.assertTrue(any('found 0' in e for e in check(root)[0]))
            new_file = source.with_name('NewScreen.swift')
            new_file.write_text('Text(name)')
            self.assertTrue(any('Add source component' in e for e in check(root)[0]))


if __name__ == '__main__':
    unittest.main()

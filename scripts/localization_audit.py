#!/usr/bin/env python3
"""Token-aware guard for hard-coded app-owned display text (no SDK/dependencies).

This is deliberately not a Swift/Kotlin compiler or a data-flow analyser. It
understands comments, raw/multiline strings, interpolation and balanced calls;
unknown/dynamic display producers remain part of the manual source inventory.
"""
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
import argparse
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {'ios': ('ios/TacticalMaps', '.swift'),
           'android': ('android/app/src/main/java/com/tacmap', '.kt')}
GENERATED = {
    'android/app/src/main/java/com/tacmap/localization/LocalizedStringIds.kt',
    'android/app/src/main/java/com/tacmap/localization/Messages.kt',
    'android/app/src/main/java/com/tacmap/localization/SupportedLanguage.kt',
    'ios/TacticalMaps/Util/Messages.swift',
    'ios/TacticalMaps/Util/SupportedLanguage.swift',
}
DISPLAY_CALLS = {'Text', 'Button', 'Label', 'Toggle', 'Picker', 'Section',
                 'TextField', 'SecureField', 'TextEditor', 'TextButton',
                 'navigationTitle', 'navigationSubtitle', 'accessibilityLabel',
                 'accessibilityHint', 'accessibilityValue', 'alert',
                 'confirmationDialog', 'setTitle', 'setMessage', 'setContentTitle',
                 'setContentText', 'setTicker', 'setHint', 'setText',
                 'setPositiveButton', 'setNegativeButton', 'setNeutralButton',
                 'Caption', 'row', 'navRow', 'sectionHeader', 'metric'}
DISPLAY_ARGS = {'text', 'title', 'message', 'hint', 'placeholder', 'label',
                'displayName', 'contentDescription', 'accessibilityLabel',
                'accessibilityHint', 'accessibilityValue', 'errorMessage',
                'statusMessage', 'failureReason', 'recoverySuggestion'}
DISPLAY_PROPERTIES = DISPLAY_ARGS | {'errorDescription', 'localizedDescription', 'statusTitle'}


@dataclass
class Token:
    kind: str
    value: str
    start: int
    end: int
    visible: str = ''


class Lexer:
    def __init__(self, source, platform):
        self.s, self.platform = source, platform
        self.n = len(source)

    def comment_end(self, i):
        if self.s.startswith('//', i):
            end = self.s.find('\n', i)
            return self.n if end < 0 else end
        depth, j = 1, i + 2
        while j < self.n and depth:
            if self.s.startswith('/*', j): depth, j = depth + 1, j + 2
            elif self.s.startswith('*/', j): depth, j = depth - 1, j + 2
            else: j += 1
        if depth: raise ValueError('Unclosed block comment')
        return j

    def string_start(self, i):
        j = i
        if self.platform == 'ios':
            while j < self.n and self.s[j] == '#': j += 1
        return j if j < self.n and self.s[j] == '"' else None

    def balanced_end(self, i, closing):
        while i < self.n:
            if self.s.startswith(('//', '/*'), i): i = self.comment_end(i)
            elif self.string_start(i) is not None: i = self.string(i).end
            elif self.platform == 'android' and self.s[i] == "'": i = self.char_end(i)
            elif self.s[i] in '([{': i = self.balanced_end(i + 1, {'(': ')', '[': ']', '{': '}'}[self.s[i]])
            elif self.s[i] == closing: return i + 1
            else: i += 1
        raise ValueError('Unclosed interpolation')

    def char_end(self, i):
        j = i + 1
        while j < self.n:
            if self.s[j] == '\\': j += 2
            elif self.s[j] == "'": return j + 1
            else: j += 1
        raise ValueError('Unclosed character literal')

    def string(self, start):
        quote = self.string_start(start)
        hashes = self.s[start:quote]
        delimiter = '"""' if self.s.startswith('"""', quote) else '"'
        close = delimiter + hashes
        escape = '\\' + hashes
        j, visible = quote + len(delimiter), []
        while j < self.n:
            if self.s.startswith(close, j):
                end = j + len(close)
                return Token('string', self.s[start:end], start, end, ''.join(visible))
            if self.platform == 'ios' and self.s.startswith(escape + '(', j):
                j = self.balanced_end(j + len(escape) + 1, ')'); continue
            if self.platform == 'android' and self.s.startswith('${', j):
                j = self.balanced_end(j + 2, '}'); continue
            if self.platform == 'android' and self.s[j] == '$':
                match = re.match(r'\$[\w]+', self.s[j:])
                if match: j += len(match[0]); continue
            if self.s.startswith(escape, j) and not (self.platform == 'android' and delimiter == '"""'):
                k = j + len(escape)
                if k >= self.n: break
                # Keep escaped letters (including Unicode escapes) reviewable.
                visible.append({'n': '\n', 't': '\t', 'r': '\r'}.get(self.s[k], self.s[k]))
                j = k + 1; continue
            visible.append(self.s[j]); j += 1
        raise ValueError('Unclosed string literal')

    def tokens(self):
        result, i = [], 0
        while i < self.n:
            if self.s[i].isspace(): i += 1; continue
            if self.s.startswith(('//', '/*'), i): i = self.comment_end(i); continue
            if self.string_start(i) is not None:
                token = self.string(i); result.append(token); i = token.end; continue
            if self.platform == 'android' and self.s[i] == "'":
                end = self.char_end(i); result.append(Token('char', self.s[i:end], i, end)); i = end; continue
            match = re.match(r'[\w]+', self.s[i:])
            if match:
                end = i + len(match[0]); result.append(Token('word', match[0], i, end)); i = end
            else:
                result.append(Token('symbol', self.s[i], i, i + 1)); i += 1
        return result


def pairs(tokens):
    stack, result = [], {}
    for i, token in enumerate(tokens):
        if token.kind != 'symbol': continue
        if token.value in '([{': stack.append(i)
        elif token.value in ')]}':
            if not stack or tokens[stack[-1]].value != {')': '(', ']': '[', '}': '{'}[token.value]:
                raise ValueError('Unbalanced source delimiters')
            start = stack.pop(); result[start] = i
    if stack: raise ValueError('Unclosed source delimiters')
    return result


def args(tokens, start, end, matched):
    begin, i = start, start
    while i < end:
        if i in matched: i = matched[i] + 1; continue
        if tokens[i].value == ',':
            yield begin, i
            begin = i + 1
        i += 1
    if begin < end: yield begin, end


def argument(tokens, start, end):
    if start + 1 < end and tokens[start].kind == 'word' and tokens[start + 1].value in (':', '='):
        return tokens[start].value, start + 2, end
    return None, start, end


def has_words(visible):
    # A format directive is not English prose; unit/name suffixes still get reviewed.
    visible = re.sub(r'%(?:\d+\$)?[-+ #0]*(?:\d+)?(?:\.\d+)?(?:ll|l)?[a-zA-Z@%]', '', visible)
    return any(c.isalpha() for c in visible)


def scan(source, platform, path='<fixture>'):
    tokens = Lexer(source, platform).tokens()
    matched = pairs(tokens)
    calls, protected, sinks = [], [], []
    for i, token in enumerate(tokens):
        if token.value != '(' or i == 0 or tokens[i - 1].kind != 'word': continue
        name = tokens[i - 1].value
        receiver = tokens[i - 3].value if i >= 3 and tokens[i - 2].value == '.' else ''
        arguments = [argument(tokens, a, b) for a, b in args(tokens, i + 1, matched[i], matched)]
        calls.append((name, receiver, i, matched[i], arguments))
        localized = receiver == 'L10n' and name in ('text', 'quantity')
        localized |= name in ('NSLocalizedString', 'stringResource', 'pluralStringResource', 'getString', 'getQuantityString')
        localized |= name == 'String' and any(label == 'localized' for label, _, _ in arguments)
        if localized:
            protected.append((i, matched[i]))
            # A translated noun must not receive an English plural suffix.
            # Match tokens rather than comments or text inside the format string.
            for _, a, b in arguments[1:]:
                for k in range(a, b - 2):
                    if tokens[k].value == '\"\"' and tokens[k + 1].value in ('else', ':') and tokens[k + 2].value in ('\"s\"', '\"es\"'):
                        sinks.append(('cached.pluralSuffix', k + 2, k + 3))
        for index, (label, a, b) in enumerate(arguments):
            if (label in DISPLAY_ARGS or
                    (name in DISPLAY_CALLS and index == 0 and label in (None, 'verbatim')) or
                    (name == 'SettingRow' and index == 2)):
                sinks.append((name + ('.' + label if label else ''), a, b))
    # Directly stored display labels and returned error descriptions need review too.
    for i, token in enumerate(tokens):
        if token.kind != 'word' or token.value not in DISPLAY_PROPERTIES: continue
        declaration = i > 0 and tokens[i - 1].value in ('val', 'var', 'let')
        assignment = i + 1 < len(tokens) and tokens[i + 1].value == '='
        if not declaration and not assignment: continue
        j = i + 1
        if j < len(tokens) and tokens[j].value == ':':
            while j < len(tokens) and tokens[j].value not in ('=', '{', ';', ',', ')'):
                if '\n' in source[tokens[j - 1].end:tokens[j].start]: break
                j += 1
        if j < len(tokens) and tokens[j].value == '=' and j + 1 < len(tokens) and tokens[j + 1].kind == 'string':
            sinks.append(('property.' + token.value, j + 1, j + 2))
        if j in matched and tokens[j].value == '{':
            # A computed display property may contain multiple switch branches.
            sinks.append(('property.' + token.value, j + 1, matched[j]))
    # Enum constructor arguments are retained for the process lifetime. Resolve
    # their display keys on access; eagerly calling L10n here freezes the language.
    if platform == 'android':
        for i in range(len(tokens) - 3):
            if [t.value for t in tokens[i:i + 2]] != ['enum', 'class']: continue
            j = i + 3
            display_indices = set()
            if j in matched and tokens[j].value == '(':
                for number, (a, b) in enumerate(args(tokens, j + 1, matched[j], matched)):
                    if any(t.value in DISPLAY_PROPERTIES for t in tokens[a:b]): display_indices.add(number)
                j = matched[j] + 1
            if j not in matched or tokens[j].value != '{': continue
            end, k = matched[j], j + 1
            while k < end and tokens[k].value != ';':
                if tokens[k].value == '@':
                    k += 2
                    while k + 1 < end and tokens[k].value == '.': k += 2
                    if k in matched: k = matched[k] + 1
                    continue
                if tokens[k].kind == 'word' and k + 1 in matched and tokens[k + 1].value == '(':
                    stop = matched[k + 1]
                    for number, (a, b) in enumerate(args(tokens, k + 2, stop, matched)):
                        if any(receiver == 'L10n' and name == 'text' and a <= call < b
                               for name, receiver, call, _, _ in calls):
                            sinks.append(('cached.enum.' + tokens[i + 2].value, a, b))
                        elif number in display_indices:
                            sinks.append(('enum.' + tokens[i + 2].value, a, b))
                    k = stop + 1
                else: k += 1
    found = {}
    for sink, a, b in sinks:
        for index in range(a, b):
            token = tokens[index]
            if token.kind != 'string' or not has_words(token.visible): continue
            if not sink.startswith('cached.') and any(start < index < end for start, end in protected): continue
            # Closures are checked through their own UI calls, not as arbitrary code.
            if any(tokens[start].value == '{' and a <= start < index < end <= b for start, end in matched.items()) and not sink.startswith('property.'):
                continue
            identity = (token.start, token.end)
            if identity in found: continue
            key_data = json.dumps([path, sink, token.value], ensure_ascii=False)
            found[identity] = {'id': hashlib.sha256(key_data.encode()).hexdigest()[:16],
                               'path': path, 'line': source.count('\n', 0, token.start) + 1,
                               'sink': sink, 'literal': token.value}
    return list(found.values())


def source_files(root=ROOT):
    for platform, (directory, suffix) in SOURCES.items():
        for path in sorted((root / directory).rglob('*' + suffix)):
            relative = path.relative_to(root).as_posix()
            if relative not in GENERATED: yield platform, relative, path


def audit(root=ROOT):
    findings, failures = [], []
    for platform, relative, path in source_files(root):
        try: findings.extend(scan(path.read_text(), platform, relative))
        except ValueError as error: failures.append(f'{relative}: cannot audit: {error}')
    return findings, failures


def check(root=ROOT):
    findings, errors = audit(root)
    policy = json.loads((root / 'localization/display-text-exceptions.json').read_text())
    by_id = {entry['id']: entry for entry in policy}
    if len(by_id) != len(policy): errors.append('Duplicate display-text exception IDs')
    counts = Counter(item['id'] for item in findings)
    for finding in findings:
        exception = by_id.get(finding['id'])
        if exception is None:
            errors.append(f"{finding['path']}:{finding['line']}: hard-coded display text in {finding['sink']}: {finding['literal']}")
        elif any(exception.get(k) != finding[k] for k in ('path', 'sink', 'literal')):
            errors.append(f"Exception metadata mismatch: {finding['id']}")
    for key, exception in by_id.items():
        if not exception.get('reason', '').strip(): errors.append(f'Exception needs rationale: {key}')
        if counts[key] != exception.get('occurrences'):
            errors.append(f'Exception {key}: expected {exception.get("occurrences")} occurrences, found {counts[key]}; review additions/removals')
    inventory = json.loads((root / 'localization/source-inventory.json').read_text())
    actual = {relative for _, relative, _ in source_files(root)}
    registered = [entry['path'] for entry in inventory]
    if len(set(registered)) != len(registered): errors.append('Duplicate source inventory entries')
    for path in sorted(actual - set(registered)): errors.append(f'Add source component to localisation inventory: {path}')
    for path in sorted(set(registered) - actual): errors.append(f'Remove stale localisation inventory component: {path}')
    for entry in inventory:
        if not entry.get('area') or entry.get('reviewStatus') not in ('manual-review-needed', 'reviewed-no-display-text', 'language-and-device-reviewed'):
            errors.append(f'Inventory component needs area/valid review status: {entry["path"]}')
        if entry.get('reviewStatus') != 'manual-review-needed' and not entry.get('evidence', '').strip():
            errors.append(f'Inventory review needs evidence: {entry["path"]}')
        expected_platform = 'ios' if entry['path'].startswith('ios/') else 'android'
        if entry.get('platform') != expected_platform:
            errors.append(f'Inventory platform mismatch: {entry["path"]}')
    return errors, len(findings), len(actual)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', action='store_true', help='Print raw findings for review; does not change exceptions')
    options = parser.parse_args()
    if options.report:
        findings, failures = audit()
        print(json.dumps({'findings': findings, 'errors': failures}, ensure_ascii=False, indent=2))
        return bool(failures)
    errors, exceptions, files = check()
    for error in errors: print(error)
    if not errors: print(f'Localisation display guard passed: {files} source components; {exceptions} explicitly reviewed literal occurrences.')
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())

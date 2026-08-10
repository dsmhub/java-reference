#!/usr/bin/env python3
"""
fixture-housekeeper: manual inject/remove of testgen logger/config fixture boilerplate.

Given a file of fully-qualified SUT class names, this hand tool adds or removes the
standard fixture wiring (field + setup()/tearDown() install/restore lines, plus either
a testsupport import or an embedded nested fixture class) in the matching *Test.java
files under a test source root.

Contract versus the Java FixturePlacementMigrator: that tool DETECTS facades by
scanning the SUT; this tool performs NO detection. It trusts the operator's class
list and applies the fixed templates below. Anchor-based and skip-not-corrupt: any
file that does not match expectations is reported as SKIP and left byte-identical.

Usage:
    python fixture_housekeeper.py --classes <file> --test-root <src/test/java>
        --action <inject-nested|remove-nested|inject-shared|remove-shared>
        --fixture <logger|config|both> [--dry-run]
"""

import argparse
import difflib
import re
import sys
from pathlib import Path


# =============================================================================
# TEMPLATE CONSTANTS — MUST TRACK THE GENERATOR.
#
# Everything in this section is a frozen copy of what testgen FixtureEmitter and
# FixturePlacementMigrator actually emit (captured 2026-08-10 from the corpus in
# operation/wip/tasks/testgenlite-v35rc-boilerdet-a11 at commit 4293d82).
# If the generator's fixture shape changes, these constants MUST be re-derived
# from freshly emitted files or the tool will stop matching real tests.
# =============================================================================

NESTED_TRACE_LOGGER = '''\
    private static final class TraceLoggerFixture {
      private final Map<String, Object> originals = new LinkedHashMap<>();

      private ChannelWriter metricLogMock;

      private ChannelWriter activityLogMock;

      public ChannelWriter metricLogMock() {
        return metricLogMock;
      }

      public ChannelWriter activityLogMock() {
        return activityLogMock;
      }

      /**
       * Swaps every delegate for a test double; restore() afterwards is mandatory.
       */
      public void install() throws Exception {
        TraceLogger target = TraceLogger.getInstance(TraceLoggerFixture.class);
        Field metricLogField = TraceLogger.class.getDeclaredField("metricLog");
        metricLogField.setAccessible(true);
        originals.put("metricLog", metricLogField.get(target));
        metricLogMock = Mockito.mock(ChannelWriter.class);
        metricLogField.set(target, metricLogMock);
        Field activityLogField = TraceLogger.class.getDeclaredField("activityLog");
        activityLogField.setAccessible(true);
        originals.put("activityLog", activityLogField.get(target));
        activityLogMock = Mockito.mock(ChannelWriter.class);
        activityLogField.set(target, activityLogMock);
      }

      public void restore() throws Exception {
        TraceLogger target = TraceLogger.getInstance(TraceLoggerFixture.class);
        if (originals.containsKey("metricLog")) {
          Field metricLogField = TraceLogger.class.getDeclaredField("metricLog");
          metricLogField.setAccessible(true);
          metricLogField.set(target, originals.get("metricLog"));
        }
        if (originals.containsKey("activityLog")) {
          Field activityLogField = TraceLogger.class.getDeclaredField("activityLog");
          activityLogField.setAccessible(true);
          activityLogField.set(target, originals.get("activityLog"));
        }
      }
    }'''

NESTED_AUDIT_LOG = '''\
    private static final class AuditLogFixture {
      private final Map<String, Object> originals = new LinkedHashMap<>();

      private AuditLog.LogWriter writerMock;

      public AuditLog.LogWriter writerMock() {
        return writerMock;
      }

      /**
       * Swaps every delegate for a test double; restore() afterwards is mandatory.
       */
      public void install() throws Exception {
        Field writerField = AuditLog.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        originals.put("writer", writerField.get(null));
        writerMock = Mockito.mock(AuditLog.LogWriter.class);
        writerField.set(null, writerMock);
      }

      public void restore() throws Exception {
        if (originals.containsKey("writer")) {
          Field writerField = AuditLog.class.getDeclaredField("writer");
          writerField.setAccessible(true);
          writerField.set(null, originals.get("writer"));
        }
      }
    }'''

NESTED_SETTINGS_HUB = '''\
    private static final class SettingsHubFixture {
      private final Map<String, Object> originals = new LinkedHashMap<>();

      private LinkedHashMap<Object, Object> valuesValues;

      public LinkedHashMap<Object, Object> valuesValues() {
        return valuesValues;
      }

      /**
       * Swaps every delegate for a test double; restore() afterwards is mandatory.
       */
      public void install() throws Exception {
        Field valuesField = SettingsHub.class.getDeclaredField("values");
        valuesField.setAccessible(true);
        originals.put("values", valuesField.get(null));
        valuesValues = new LinkedHashMap<>((Map<?, ?>) originals.get("values"));
        valuesField.set(null, valuesValues);
      }

      public void restore() throws Exception {
        if (originals.containsKey("values")) {
          Field valuesField = SettingsHub.class.getDeclaredField("values");
          valuesField.setAccessible(true);
          valuesField.set(null, originals.get("values"));
        }
      }
    }'''


class Fixture:
    """One known fixture: its wiring lines, imports, and nested class text."""

    def __init__(self, simple, kind, field_name, nested_text, nested_imports):
        self.simple = simple
        self.kind = kind
        self.field_name = field_name
        self.field_decl = ('private final %s %s = new %s();'
                           % (simple, field_name, simple))
        self.install_line = '%s.install();' % field_name
        self.restore_line = '%s.restore();' % field_name
        self.shared_import = 'import testsupport.%s;' % simple
        self.nested_anchor = 'private static final class %s' % simple
        self.nested_text = nested_text
        self.nested_imports = nested_imports


FIXTURES = {
    'TraceLoggerFixture': Fixture(
        'TraceLoggerFixture', 'logger', 'traceLoggerFixture', NESTED_TRACE_LOGGER,
        ['java.lang.reflect.Field', 'java.util.LinkedHashMap', 'java.util.Map',
         'labshape.ChannelWriter', 'labshape.TraceLogger', 'org.mockito.Mockito']),
    'AuditLogFixture': Fixture(
        'AuditLogFixture', 'logger', 'auditLogFixture', NESTED_AUDIT_LOG,
        ['com.field.support.AuditLog', 'java.lang.reflect.Field',
         'java.util.LinkedHashMap', 'java.util.Map', 'org.mockito.Mockito']),
    'SettingsHubFixture': Fixture(
        'SettingsHubFixture', 'config', 'settingsHubFixture', NESTED_SETTINGS_HUB,
        ['com.field.support.SettingsHub', 'java.lang.reflect.Field',
         'java.util.LinkedHashMap', 'java.util.Map']),
}

# Emission order the generator uses: logger fixture first, config fixture second.
# setup() installs run in this order; tearDown() restores run in reverse.
CANONICAL_ORDER = ['TraceLoggerFixture', 'AuditLogFixture', 'SettingsHubFixture']

# Which logger fixture belongs to which SUT package family (tracks the corpus).
LOGGER_BY_PACKAGE = [('labshape', 'TraceLoggerFixture'),
                     ('com.field', 'AuditLogFixture')]

IMPORT_BEFORE_EACH = 'import org.junit.jupiter.api.BeforeEach;'
IMPORT_AFTER_EACH = 'import org.junit.jupiter.api.AfterEach;'

# ======================= end of generator-tracking constants =================


class SkipFile(Exception):
    """Raised when a file does not match expectations; the file is left alone."""


class JavaFile:
    """A test file as lines, with its newline style preserved end to end."""

    def __init__(self, path):
        self.path = path
        raw = path.read_bytes()
        try:
            text = raw.decode('utf-8')
        except UnicodeDecodeError:
            raise SkipFile('not valid UTF-8')
        self.eol = self._detect_eol(text)
        self.ends_with_newline = text.endswith(('\n', '\r\n'))
        self.original_text = text
        self.lines = text.replace('\r\n', '\n').split('\n')
        if self.ends_with_newline:
            self.lines.pop()

    @staticmethod
    def _detect_eol(text):
        crlf = text.count('\r\n')
        if text.count('\r') != crlf:
            raise SkipFile('mixed or bare-CR line endings')
        if crlf and crlf != text.count('\n'):
            raise SkipFile('mixed CRLF/LF line endings')
        return '\r\n' if crlf else '\n'

    def render(self):
        text = self.eol.join(self.lines)
        return text + self.eol if self.ends_with_newline else text

    def changed(self):
        return self.render() != self.original_text


def strip_comments_and_strings(text):
    """Blanks out //, /* */ comments and string/char literals for brace counting."""
    out = []
    i, n, state = 0, len(text), None
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = 'line'
                i += 2
                continue
            if c == '/' and nxt == '*':
                state = 'block'
                i += 2
                continue
            if c in ('"', "'"):
                state = c
                i += 1
                continue
            out.append(c)
        elif state == 'line':
            if c == '\n':
                state = None
                out.append(c)
        elif state == 'block':
            if c == '*' and nxt == '/':
                state = None
                i += 1
            elif c == '\n':
                out.append(c)
        else:
            if c == '\\':
                i += 1
            elif c == state:
                state = None
            elif c == '\n':
                state = None    # unterminated literal: fail safe at line end
                out.append(c)
        i += 1
    return ''.join(out)


def check_brace_balance(java):
    """Whole-file brace sanity; a mismatch means we must not touch the file."""
    stripped = strip_comments_and_strings('\n'.join(java.lines))
    depth = 0
    for c in stripped:
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        if depth < 0:
            raise SkipFile('unbalanced braces')
    if depth != 0:
        raise SkipFile('unbalanced braces')


def code_lines(java):
    """Per-line comment/string-stripped view used for anchors and depth scans."""
    return strip_comments_and_strings('\n'.join(java.lines)).split('\n')


def find_class_block(java, test_simple_name):
    """Returns (open_idx, close_idx) of the test class declaration and its brace."""
    pattern = re.compile(
        r'^\s*(public\s+)?(final\s+)?class\s+%s\b.*\{\s*$' % re.escape(test_simple_name))
    stripped = code_lines(java)
    open_idx = next((i for i, line in enumerate(stripped) if pattern.match(line)), None)
    if open_idx is None:
        raise SkipFile('class anchor "class %s {" not found' % test_simple_name)
    close_idx = matching_close(stripped, open_idx)
    if close_idx is None:
        raise SkipFile('class closing brace not found')
    return open_idx, close_idx


def matching_close(stripped, open_idx):
    """Line index whose brace returns the depth opened at open_idx to zero."""
    depth = 0
    for i in range(open_idx, len(stripped)):
        depth += stripped[i].count('{') - stripped[i].count('}')
        if depth == 0:
            return i
    return None


def find_lifecycle(java, annotation, p48_name):
    """
    Finds the @BeforeEach/@AfterEach method; enforces the P48 name.

    Returns (annotation_idx, signature_idx, close_idx) or None when absent.
    """
    hits = [i for i, line in enumerate(java.lines) if line.strip() == annotation]
    if not hits:
        return None
    if len(hits) > 1:
        raise SkipFile('multiple %s methods' % annotation)
    ann_idx = hits[0]
    sig_idx = next((i for i in range(ann_idx + 1, len(java.lines))
                    if java.lines[i].strip()), None)
    sig = re.match(r'^\s*public\s+void\s+(\w+)\s*\([^)]*\)[^{]*\{\s*$',
                   java.lines[sig_idx] if sig_idx is not None else '')
    if not sig:
        raise SkipFile('%s not followed by a plain method signature' % annotation)
    if sig.group(1) != p48_name:
        raise SkipFile('%s method is named %s, expected %s'
                       % (annotation, sig.group(1), p48_name))
    close_idx = matching_close(code_lines(java), sig_idx)
    if close_idx is None:
        raise SkipFile('%s method has no closing brace' % p48_name)
    return ann_idx, sig_idx, close_idx


def member_indent(java, class_open):
    """Indentation of class members, taken from the first member line found."""
    for line in java.lines[class_open + 1:]:
        if line.strip():
            return line[:len(line) - len(line.lstrip())] or '  '
    return '  '


def host_package(java):
    for line in java.lines:
        match = re.match(r'^\s*package\s+([\w.]+)\s*;', line)
        if match:
            return match.group(1)
    raise SkipFile('package declaration not found')


def word_used_outside_imports(java, word):
    """True when the simple name occurs anywhere except import lines."""
    body = '\n'.join(line for line in java.lines
                     if not line.strip().startswith('import '))
    return re.search(r'\b%s\b' % re.escape(word), body) is not None


def add_import(java, import_line):
    """Inserts an import in alphabetical order; no-op when already present."""
    if any(line.strip() == import_line for line in java.lines):
        return
    import_idxs = [i for i, line in enumerate(java.lines)
                   if line.strip().startswith('import ')]
    if import_idxs:
        after = [i for i in import_idxs if java.lines[i].strip() > import_line]
        at = after[0] if after else import_idxs[-1] + 1
    else:
        pkg_idx = next(i for i, line in enumerate(java.lines)
                       if line.strip().startswith('package '))
        java.lines.insert(pkg_idx + 1, '')
        at = pkg_idx + 2
    java.lines.insert(at, import_line)


def remove_import_if_unused(java, import_line):
    """Drops an import the tool may have added, when its name is no longer used."""
    simple = import_line.rstrip(';').split('.')[-1]
    for i, line in enumerate(java.lines):
        if line.strip() == import_line:
            if not word_used_outside_imports(java, simple):
                del java.lines[i]
            return


def cap_blank_runs(java):
    """Edits must never grow whitespace: blank runs are capped at two lines."""
    out, run = [], 0
    for line in java.lines:
        run = run + 1 if not line.strip() else 0
        if run <= 2:
            out.append(line)
    java.lines = out


def fixture_presence(java, fixture):
    """Returns 'shared', 'nested', 'wired' (lines only), or None."""
    text = '\n'.join(java.lines)
    if fixture.shared_import in text:
        return 'shared'
    if fixture.nested_anchor in text:
        return 'nested'
    if fixture.field_decl in text or fixture.install_line in text:
        return 'wired'
    return None


def check_no_name_conflict(java, fixture):
    """A same-named member that is not our exact wiring means hands off."""
    if word_used_outside_imports(java, fixture.field_name):
        raise SkipFile('field name %s already in use' % fixture.field_name)
    if word_used_outside_imports(java, fixture.simple):
        raise SkipFile('type name %s already in use' % fixture.simple)


# ----------------------------------------------------------------- injection


def ensure_lifecycle_methods(java, class_open, class_close, indent):
    """Creates setup()/tearDown() with P48 names when absent; returns nothing."""
    if find_lifecycle(java, '@BeforeEach', 'setup') is None:
        at = first_annotation_line(java, class_open, class_close)
        block = [indent + '@BeforeEach',
                 indent + 'public void setup() throws Exception {',
                 indent + '}',
                 '']
        java.lines[at:at] = block
        add_import(java, IMPORT_BEFORE_EACH)
    if find_lifecycle(java, '@AfterEach', 'tearDown') is None:
        _, _, setup_close = find_lifecycle(java, '@BeforeEach', 'setup')
        at = setup_close + 1
        if at < len(java.lines) and not java.lines[at].strip():
            at += 1
        block = [indent + '@AfterEach',
                 indent + 'public void tearDown() throws Exception {',
                 indent + '}',
                 '']
        java.lines[at:at] = block
        add_import(java, IMPORT_AFTER_EACH)


def first_annotation_line(java, class_open, class_close):
    """Insertion point for a created setup(): before the first method annotation."""
    for i in range(class_open + 1, class_close):
        if java.lines[i].strip().startswith('@'):
            return i
    return class_close


def insert_field(java, fixture, class_open, indent):
    """Adds the fixture field as a first member, after canonical-earlier fields."""
    at = class_open + 1
    for name in CANONICAL_ORDER:
        if name == fixture.simple:
            break
        earlier = FIXTURES[name]
        if at < len(java.lines) and java.lines[at].strip() == earlier.field_decl:
            at += 1
            if at < len(java.lines) and not java.lines[at].strip():
                at += 1
    java.lines[at:at] = [indent + fixture.field_decl, '']


def insert_install(java, fixture, indent):
    """Install goes at the top of setup(); logger installs before config ones."""
    _, sig_idx, close_idx = find_lifecycle(java, '@BeforeEach', 'setup')
    body_indent = indent * 2
    at = sig_idx + 1
    if fixture.kind == 'config':
        at = after_last_known_line(java, sig_idx, close_idx,
                                   known_install_lines('logger'), at)
    java.lines.insert(at, body_indent + fixture.install_line)


def insert_restore(java, fixture, indent):
    """Restore order mirrors install in reverse: config restores before logger."""
    _, sig_idx, close_idx = find_lifecycle(java, '@AfterEach', 'tearDown')
    body_indent = indent * 2
    at = sig_idx + 1
    if fixture.kind == 'logger':
        at = after_last_known_line(java, sig_idx, close_idx,
                                   known_restore_lines('config'), at)
    java.lines.insert(at, body_indent + fixture.restore_line)


def known_install_lines(kind):
    return [f.install_line for f in FIXTURES.values() if f.kind == kind]


def known_restore_lines(kind):
    return [f.restore_line for f in FIXTURES.values() if f.kind == kind]


def after_last_known_line(java, sig_idx, close_idx, known, default):
    """Index just past the last known line inside the method body, else default."""
    at = default
    for i in range(sig_idx + 1, close_idx):
        if java.lines[i].strip() in known:
            at = i + 1
    return at


def inject(java, fixture, placement, test_root):
    """Injects one fixture (shared or nested placement) into the file."""
    presence = fixture_presence(java, fixture)
    if presence:
        raise SkipFile('%s already present (%s)' % (fixture.simple, presence))
    check_no_name_conflict(java, fixture)

    if placement == 'shared':
        support = test_root / 'testsupport' / (fixture.simple + '.java')
        if not support.is_file():
            raise SkipFile('missing %s' % support)

    test_name = Path(java.path).stem
    class_open, class_close = find_class_block(java, test_name)
    indent = member_indent(java, class_open)
    pkg = host_package(java)

    if placement == 'shared':
        add_import(java, fixture.shared_import)
    else:
        block = fixture.nested_text.split('\n')
        if java.lines[class_close - 1].strip():
            block = [''] + block
        java.lines[class_close:class_close] = block
        for fqcn in fixture.nested_imports:
            if fqcn.rsplit('.', 1)[0] != pkg:
                add_import(java, 'import %s;' % fqcn)

    class_open, class_close = find_class_block(java, test_name)
    insert_field(java, fixture, class_open, indent)
    class_open, class_close = find_class_block(java, test_name)
    ensure_lifecycle_methods(java, class_open, class_close, indent)
    insert_install(java, fixture, indent)
    insert_restore(java, fixture, indent)


# ------------------------------------------------------------------- removal


def remove_line(java, content):
    """Removes the first line with this stripped content; True when found."""
    for i, line in enumerate(java.lines):
        if line.strip() == content:
            del java.lines[i]
            return True
    return False


def remove_field(java, fixture):
    """Removes the field line plus the single blank separator the tool adds."""
    for i, line in enumerate(java.lines):
        if line.strip() == fixture.field_decl:
            del java.lines[i]
            if i < len(java.lines) and not java.lines[i].strip():
                del java.lines[i]
            return


def remove_nested_block(java, fixture):
    """
    Cuts the embedded nested class block, located by its anchor line.

    Inject adds a separating blank line only when the block would touch the
    previous member; removal mirrors that exactly — the single preceding blank
    goes only when its own predecessor is non-blank. Without this pairing a
    nested round trip on a generator-shaped file would grow the file.
    """
    stripped = code_lines(java)
    start = next((i for i, line in enumerate(java.lines)
                  if line.strip().startswith(fixture.nested_anchor)), None)
    if start is None:
        raise SkipFile('nested anchor missing for %s' % fixture.simple)
    end = matching_close(stripped, start)
    if end is None:
        raise SkipFile('nested block unbalanced for %s' % fixture.simple)
    del java.lines[start:end + 1]
    if (start > 1 and not java.lines[start - 1].strip()
            and java.lines[start - 2].strip()):
        del java.lines[start - 1]


def remove_empty_lifecycle(java, annotation, p48_name):
    """Drops a lifecycle method the removal emptied, plus its trailing blank."""
    found = find_lifecycle(java, annotation, p48_name)
    if found is None:
        return
    ann_idx, sig_idx, close_idx = found
    body = [line for line in java.lines[sig_idx + 1:close_idx] if line.strip()]
    if body:
        return
    del java.lines[ann_idx:close_idx + 1]
    if ann_idx < len(java.lines) and not java.lines[ann_idx].strip():
        del java.lines[ann_idx]
    import_line = (IMPORT_BEFORE_EACH if annotation == '@BeforeEach'
                   else IMPORT_AFTER_EACH)
    remove_import_if_unused(java, import_line)


def remove(java, fixture, placement):
    """Removes one fixture's wiring; strictly the reverse of inject."""
    presence = fixture_presence(java, fixture)
    if presence is None:
        raise SkipFile('%s not present' % fixture.simple)
    if presence == 'wired':
        raise SkipFile('%s wiring present without import or nested class'
                       % fixture.simple)
    if presence != placement:
        raise SkipFile('%s present as %s; use remove-%s'
                       % (fixture.simple, presence, presence))

    if placement == 'shared':
        remove_line(java, fixture.shared_import)
    else:
        remove_nested_block(java, fixture)

    remove_field(java, fixture)
    remove_line(java, fixture.install_line)
    remove_line(java, fixture.restore_line)
    remove_empty_lifecycle(java, '@BeforeEach', 'setup')
    remove_empty_lifecycle(java, '@AfterEach', 'tearDown')

    if placement == 'nested':
        for fqcn in fixture.nested_imports:
            remove_import_if_unused(java, 'import %s;' % fqcn)


# ------------------------------------------------------------------ resolve


def read_classes(classes_file):
    """SUT class names, one per line; blanks and # comments ignored."""
    names = []
    for line in classes_file.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#'):
            names.append(line)
    return names


def test_file_for(test_root, fqcn):
    parts = fqcn.split('.')
    return test_root.joinpath(*parts[:-1], parts[-1] + 'Test.java')


def fixtures_for_inject(fqcn, kinds):
    """Registry lookup, no detection: which fixtures inject for this SUT."""
    chosen = []
    if 'logger' in kinds:
        pkg = fqcn.rsplit('.', 1)[0]
        name = next((fix for prefix, fix in LOGGER_BY_PACKAGE
                     if pkg == prefix or pkg.startswith(prefix + '.')), None)
        if name is None:
            raise SkipFile('no logger fixture mapping for package of %s' % fqcn)
        chosen.append(FIXTURES[name])
    if 'config' in kinds:
        chosen.append(FIXTURES['SettingsHubFixture'])
    return in_canonical_order(chosen)


def fixtures_for_remove(java, kinds):
    """Whichever known fixtures of the requested kinds are actually in the file."""
    present = [f for f in FIXTURES.values()
               if f.kind in kinds and fixture_presence(java, f)]
    if not present:
        raise SkipFile('no %s fixture present' % '/'.join(sorted(kinds)))
    return in_canonical_order(present)


def in_canonical_order(fixtures):
    return sorted(fixtures, key=lambda f: CANONICAL_ORDER.index(f.simple))


# --------------------------------------------------------------------- main


def process_class(fqcn, args, test_root):
    """Runs the action for one SUT class; returns (status, detail) for the report."""
    path = test_file_for(test_root, fqcn)
    if not path.is_file():
        return 'SKIP', 'test file not found: %s' % path

    java = JavaFile(path)
    check_brace_balance(java)
    find_class_block(java, path.stem)

    mode, placement = args.action.split('-')
    kinds = {'both': {'logger', 'config'}}.get(args.fixture, {args.fixture})

    if mode == 'inject':
        chosen = fixtures_for_inject(fqcn, kinds)
    else:
        chosen = fixtures_for_remove(java, kinds)

    applied, skipped = [], []
    for fixture in chosen:
        before = list(java.lines)
        try:
            if mode == 'inject':
                inject(java, fixture, placement, test_root)
            else:
                remove(java, fixture, placement)
            applied.append(fixture.simple)
        except SkipFile as reason:
            java.lines = before
            skipped.append('%s (%s)' % (fixture.simple, reason))

    if not applied:
        return 'SKIP', '; '.join(skipped) + ' -> %s' % path

    cap_blank_runs(java)
    detail = '%s -> %s' % ('+'.join(applied), path)
    if skipped:
        detail += ' [skipped: %s]' % '; '.join(skipped)
    if args.dry_run:
        diff = difflib.unified_diff(
            java.original_text.splitlines(), java.render().splitlines(),
            fromfile=str(path), tofile='%s (%s)' % (path, args.action), lineterm='')
        print('\n'.join(diff))
        return 'DRY-RUN', detail
    path.write_bytes(java.render().encode('utf-8'))
    return mode.upper(), detail


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog='fixture_housekeeper',
        description='Inject or remove testgen logger/config fixture boilerplate.')
    parser.add_argument('--classes', required=True, type=Path,
                        help='file with one fully-qualified SUT class per line')
    parser.add_argument('--test-root', required=True, type=Path,
                        help='test source root, e.g. <project>/src/test/java')
    parser.add_argument('--action', required=True,
                        choices=['inject-nested', 'remove-nested',
                                 'inject-shared', 'remove-shared'])
    parser.add_argument('--fixture', required=True,
                        choices=['logger', 'config', 'both'])
    parser.add_argument('--dry-run', action='store_true',
                        help='print the would-be diff; write nothing')
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    if not args.classes.is_file():
        print('ERROR classes file not found: %s' % args.classes)
        return 2
    test_root = args.test_root.resolve()
    if not test_root.is_dir():
        print('ERROR test root not found: %s' % test_root)
        return 2

    counts = {}
    for fqcn in read_classes(args.classes):
        try:
            status, detail = process_class(fqcn, args, test_root)
        except SkipFile as reason:
            status, detail = 'SKIP', '%s -> %s' % (reason, test_file_for(test_root, fqcn))
        counts[status] = counts.get(status, 0) + 1
        print('[%s] %s %s' % (status, fqcn, detail))

    print('summary: ' + ', '.join('%s=%d' % kv for kv in sorted(counts.items())))
    return 0


if __name__ == '__main__':
    sys.exit(main())

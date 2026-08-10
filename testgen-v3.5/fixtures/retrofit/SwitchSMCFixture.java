package com.testgen.fixtures.retrofit;

import com.testgen.fixtures.FixtureGenerator;
import com.testgen.fixtures.SMCFixtureScanner;

import com.config.Config;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Flips EXISTING generated tests between fixture placements for the logger/config fixtures.
 *
 * SHARED -> NESTED: drops the testsupport import and embeds the rendered fixture as a private
 * nested class. NESTED -> SHARED: writes the testsupport fixture (skip-if-exists), removes the
 * nested class, adds the import. Fixture fields and setup()/tearDown() lines are placement-
 * independent and are never touched.
 *
 * Real-world hardening (defects D1/D2 from the greenmail leg): merged imports are filtered
 * (java.lang and host-package never enter), imports left unused after a flip are dropped, and
 * blank runs are capped so repeated flips cannot grow the file.
 *
 * Anchor-based and skip-not-corrupt: an unexpected file is reported and left alone. Idempotent:
 * a file already in the target placement is a SKIP.
 *
 * Usage: run main with optional arg SHARED|NESTED; no arg = Config.FIXTURE_PLACEMENT.
 */
public final class SwitchSMCFixture {

    private SwitchSMCFixture() {}

    public static void main(String[] args) throws Exception {
        String target = args.length > 0 ? args[0].toUpperCase() : Config.FIXTURE_PLACEMENT;
        if (!target.equals("SHARED") && !target.equals("NESTED")) {
            throw new IllegalArgumentException("target must be SHARED or NESTED, got: " + target);
        }

        for (String fqcn : Config.readClasses()) {
            Class<?> sut = Class.forName(fqcn.trim());
            List<SMCFixtureScanner.Facade> facades = SMCFixtureScanner.detect(sut);
            if (facades.isEmpty()) {
                System.out.println("SKIP (no facade detected): " + fqcn);
                continue;
            }

            Path testFile = Config.testSourceRoot()
                    .resolve(sut.getPackageName().replace('.', '/'))
                    .resolve(sut.getSimpleName() + Config.TEST_NAME_SUFFIX + ".java");
            if (!Files.isRegularFile(testFile)) {
                System.out.println("SKIP (no existing test file): " + testFile);
                continue;
            }

            migrate(testFile, facades, target);
        }
    }

    /** Migrates one test file to the target placement; counts per-facade outcomes. */
    private static void migrate(Path testFile, List<SMCFixtureScanner.Facade> facades,
            String target) throws Exception {
        String source = Files.readString(testFile);
        int changed = 0;

        for (SMCFixtureScanner.Facade facade : facades) {
            String simple = FixtureGenerator.fixtureSimpleName(facade);
            boolean nestedHere = source.contains("private static final class " + simple);
            boolean sharedHere = source.contains("import testsupport." + simple + ";");

            if (target.equals("NESTED") && sharedHere && !nestedHere) {
                source = toNested(source, facade, simple, testFile);
                if (source == null) {
                    return;
                }
                changed++;
            } else if (target.equals("SHARED") && nestedHere) {
                source = toShared(source, facade, simple, testFile);
                if (source == null) {
                    return;
                }
                changed++;
            }
        }

        if (changed == 0) {
            System.out.println("SKIP (already " + target + "): " + testFile);
            return;
        }
        Files.writeString(testFile, capBlankRuns(source));
        System.out.println("MIGRATED " + changed + " fixture(s) -> " + target + ": " + testFile);
    }

    /** SHARED -> NESTED: remove the import, embed the rendered fixture before the final brace. */
    private static String toNested(String source, SMCFixtureScanner.Facade facade,
            String simple, Path testFile) {
        String importLine = "import testsupport." + simple + ";";
        int importIdx = source.indexOf(importLine);
        int lastBrace = source.lastIndexOf('}');
        if (importIdx < 0 || lastBrace < 0) {
            System.out.println("SKIP (anchor missing for " + simple + "): " + testFile);
            return null;
        }

        String rendered = renderNested(facade);
        String out = source.replace(importLine + "\n", "").replace(importLine, "");
        out = mergeImports(out, fixtureImports(rendered, hostPackage(out)), testFile);
        if (out == null) {
            return null;
        }

        lastBrace = out.lastIndexOf('}');
        return out.substring(0, lastBrace) + "\n" + indent(classOf(rendered)) + "}\n";
    }

    /** NESTED -> SHARED: write the testsupport fixture, cut the nested block, add the import. */
    private static String toShared(String source, SMCFixtureScanner.Facade facade,
            String simple, Path testFile) throws Exception {
        String anchor = "private static final class " + simple;
        int start = source.indexOf(anchor);
        int open = source.indexOf('{', start);
        if (start < 0 || open < 0) {
            System.out.println("SKIP (nested anchor missing for " + simple + "): " + testFile);
            return null;
        }

        int depth = 0;
        int end = -1;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        if (end < 0) {
            System.out.println("SKIP (unbalanced braces in " + simple + "): " + testFile);
            return null;
        }

        FixtureGenerator.write(facade, Config.testSourceRoot());

        int lineStart = source.lastIndexOf('\n', start) + 1;
        String out = source.substring(0, lineStart) + source.substring(end);
        out = mergeImports(out, "import testsupport." + simple + ";\n", testFile);
        if (out == null) {
            return null;
        }
        return dropUnusedImports(out);
    }

    /** Inserts import lines (deduplicated) after the package statement. */
    private static String mergeImports(String source, String imports, Path testFile) {
        int pkgIdx = source.indexOf("package ");
        int pkgEnd = source.indexOf(';', pkgIdx);
        if (pkgIdx < 0 || pkgEnd < 0) {
            System.out.println("SKIP (package anchor missing): " + testFile);
            return null;
        }

        StringBuilder fresh = new StringBuilder();
        for (String line : imports.split("\n")) {
            if (!line.isBlank() && !source.contains(line.trim())) {
                fresh.append(line.trim()).append('\n');
            }
        }
        if (fresh.length() == 0) {
            return source;
        }

        if (source.indexOf("\nimport ") < 0) {
            return source.substring(0, pkgEnd + 1) + "\n\n" + fresh + source.substring(pkgEnd + 1);
        }
        for (String add : fresh.toString().split("\n")) {
            int insertAt = sortedInsertPoint(source, add);
            source = source.substring(0, insertAt) + add + "\n" + source.substring(insertAt);
        }
        return source;
    }


    /** R3: position keeping the import block alphabetically ordered. */
    private static int sortedInsertPoint(String source, String importLine) {
        int idx = source.indexOf("\nimport ");
        int insertAt = -1;
        int lastEnd = -1;
        while (idx >= 0) {
            int lineEnd = source.indexOf('\n', idx + 1);
            String existing = source.substring(idx + 1, lineEnd);
            if (insertAt < 0 && existing.compareTo(importLine) > 0) {
                insertAt = idx + 1;
            }
            lastEnd = lineEnd + 1;
            idx = source.indexOf("\nimport ", idx + 1);
        }
        return insertAt >= 0 ? insertAt : lastEnd;
    }

    /** D1: the fixture's import lines, filtered - java.lang and the host package never enter. */
    private static String fixtureImports(String rendered, String hostPackage) {
        StringBuilder out = new StringBuilder();
        for (String line : rendered.split("\n")) {
            if (!line.startsWith("import ")) {
                continue;
            }
            String fqcn = line.substring(7).replace(";", "").trim();
            String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
            if (pkg.equals("java.lang") || pkg.equals(hostPackage)) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** The host file's own package, for import filtering. */
    private static String hostPackage(String source) {
        int idx = source.indexOf("package ");
        int endIdx = source.indexOf(';', idx);
        return idx < 0 ? "" : source.substring(idx + 8, endIdx).trim();
    }

    /** D1: after a flip to SHARED, imports whose simple name no longer occurs are dropped. */
    private static String dropUnusedImports(String source) {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") && trimmed.endsWith(";")
                    && !trimmed.startsWith("import static")) {
                String fqcn = trimmed.substring(7, trimmed.length() - 1).trim();
                if (fqcn.startsWith("java.lang.") && fqcn.chars().filter(c -> c == '.').count() == 2) {
                    continue;   // W1 residue: java.lang imports are always redundant
                }
                String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                String body = source.substring(source.indexOf(line) + line.length());
                java.util.regex.Pattern usage = java.util.regex.Pattern.compile(
                        "\\b" + java.util.regex.Pattern.quote(simple) + "\\b");
                if (!usage.matcher(body).find()) {
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        String result = out.toString();
        return result.substring(0, result.length() - 1);
    }

    /** D2: flips must not grow whitespace - blank runs are capped at two. */
    private static String capBlankRuns(String source) {
        String unified = source.replace("\r\n", "\n");
        return unified.replaceAll("\n{4,}", "\n\n\n");
    }

    /** The fixture rendered as a full compilation unit (imports + class). */
    private static String renderNested(SMCFixtureScanner.Facade facade) {
        TypeSpec type = FixtureGenerator.nestedType(facade);
        return JavaFile.builder("migrated", type).skipJavaLangImports(true).build().toString();
    }

    private static String classOf(String rendered) {
        return rendered.substring(rendered.indexOf("private static final class"));
    }

    private static String indent(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\n")) {
            out.append(line.isBlank() ? "" : "    " + line).append('\n');
        }
        return out.toString();
    }
}

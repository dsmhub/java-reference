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
 * Adds the facade protection (install/restore + fixture) to EXISTING test classes that the
 * generator did not produce. Deterministic and anchor-based: when an expected anchor is missing
 * the file is reported and SKIPPED, never guessed at or corrupted.
 *
 * Honours the same switches as generation:
 *   Config.FIXTURE_PLACEMENT  "SHARED" = testsupport fixture files + thin calls
 *                             "NESTED" = fixture embedded privately in each injected file
 *   Config.CONFIG_PAYLOAD     "MOCK" | "SEEDED" (see FixtureGenerator)
 *
 * Run from the module root (IntelliJ Run works): reads testgen/classes.txt like the Runner.
 */
public final class InjectSMCFixture {

    private InjectSMCFixture() {}

    public static void main(String[] args) throws Exception {
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
            String source = Files.readString(testFile);
            if (source.contains("Fixture")) {
                System.out.println("SKIP (already protected): " + testFile);
                continue;
            }
            String injected = inject(source, sut, facades, testFile);
            if (injected == null) {
                continue;   // anchor missing - reported inside inject()
            }
            Files.writeString(testFile, injected);
            System.out.println("INJECTED " + facades.size() + " fixture(s): " + testFile);
        }
    }

    /**
     * TWEAK-POINT (injection anchors): the class-body anchor is the first "class <TestName> {"
     * line; setup/tearDown anchors are "void setup()" / "void tearDown()" if present, else both
     * methods are created after the fixture fields.
     */
    private static String inject(String source, Class<?> sut,
            List<SMCFixtureScanner.Facade> facades, Path testFile) throws Exception {
        String testName = sut.getSimpleName() + Config.TEST_NAME_SUFFIX;
        String classAnchor = "class " + testName + " {";
        int classIdx = source.indexOf(classAnchor);
        if (classIdx < 0) {
            System.out.println("SKIP (class anchor missing): " + testFile);
            return null;
        }
        int bodyStart = classIdx + classAnchor.length();

        StringBuilder fields = new StringBuilder();
        StringBuilder installs = new StringBuilder();
        StringBuilder restores = new StringBuilder();
        StringBuilder nestedTypes = new StringBuilder();
        StringBuilder imports = new StringBuilder();

        for (SMCFixtureScanner.Facade facade : facades) {
            String simple = FixtureGenerator.fixtureSimpleName(facade);
            String var = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
            fields.append("\n    private final ").append(simple).append(' ').append(var)
                    .append(" = new ").append(simple).append("();\n");
            installs.append("        ").append(var).append(".install();\n");
            restores.insert(0, "        " + var + ".restore();\n");
            if ("NESTED".equals(Config.FIXTURE_PLACEMENT)) {
                String rendered = renderedNested(facade);
                nestedTypes.append('\n').append(indent(classOf(rendered))).append('\n');
                imports.append(importsOf(rendered));
            } else {
                FixtureGenerator.write(facade, Config.testSourceRoot());
                imports.append("import testsupport.").append(simple).append(";\n");
            }
        }

        String out = source;
        if (imports.length() > 0) {
            int pkgEnd = out.indexOf(';', out.indexOf("package ")) + 1;
            out = out.substring(0, pkgEnd) + "\n\n" + imports + out.substring(pkgEnd);
            bodyStart = out.indexOf(classAnchor) + classAnchor.length();
        }

        boolean hasSetup = out.contains("void setup()");
        boolean hasTearDown = out.contains("void tearDown()");
        String lifecycle = "";
        if (!hasSetup) {
            lifecycle += "\n    @org.junit.jupiter.api.BeforeEach\n    void setup() throws Exception {\n"
                    + installs + "    }\n";
        }
        if (!hasTearDown) {
            lifecycle += "\n    @org.junit.jupiter.api.AfterEach\n    void tearDown() throws Exception {\n"
                    + restores + "    }\n";
        }
        out = out.substring(0, bodyStart) + fields + lifecycle + out.substring(bodyStart);

        if (hasSetup) {
            out = insertAtMethodStart(out, "void setup()", installs.toString(), testFile);
            if (out == null) {
                return null;
            }
        }
        if (hasTearDown) {
            out = insertAtMethodStart(out, "void tearDown()", restores.toString(), testFile);
            if (out == null) {
                return null;
            }
        }

        if (nestedTypes.length() > 0) {
            int lastBrace = out.lastIndexOf('}');
            out = out.substring(0, lastBrace) + nestedTypes + "}\n";
        }
        return out;
    }

    private static String insertAtMethodStart(String source, String signature, String lines,
            Path testFile) {
        int idx = source.indexOf(signature);
        int brace = source.indexOf('{', idx);
        if (idx < 0 || brace < 0) {
            System.out.println("SKIP (lifecycle anchor missing '" + signature + "'): " + testFile);
            return null;
        }
        return source.substring(0, brace + 1) + "\n" + lines + source.substring(brace + 1);
    }

    /** The nested fixture rendered as a full compilation unit (imports + class). */
    private static String renderedNested(SMCFixtureScanner.Facade facade) {
        TypeSpec type = FixtureGenerator.nestedType(facade);
        return JavaFile.builder("injected", type).skipJavaLangImports(true).build().toString();
    }

    private static String classOf(String rendered) {
        return rendered.substring(rendered.indexOf("private static final class"));
    }

    /** The fixture's own import lines, so the host file compiles without JavaPoet's help. */
    private static String importsOf(String rendered) {
        StringBuilder out = new StringBuilder();
        for (String line : rendered.split("\n")) {
            if (line.startsWith("import ")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String indent(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\n")) {
            out.append(line.isBlank() ? "" : "    " + line).append('\n');
        }
        return out.toString();
    }
}

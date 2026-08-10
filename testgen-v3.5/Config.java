package com.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Drop-in contract: Run working directory = SUT module root.
 * This folder lives at {@code MODULE/testgen/}.
 */
public final class Config {

    /** SUT module root = process working directory. */
    public static final Path MODULE = Path.of("").toAbsolutePath().normalize();

    /**
     * Production source root.
     * null → {@code MODULE/src/main/java}
     */
    public static final Path MAIN_SOURCES = null;

    /**
     * Test source root (skeletons written here).
     * null → {@code MODULE/src/test/java}
     */
    public static final Path TEST_SOURCES = null;

    public static final String TEST_NAME_SUFFIX = "Test";

    /**
     * {@code true}: inject step removes dependency-graph markers from source (no inject).
     * {@code false}: inject step writes dependency-graph markers into source.
     */
    public static final boolean STRIP = false;

    /**
     * {@code false} (default): generated stubs cover only methods declared by project classes;
     * third-party/JDK collaborators are mocked but left unstubbed.
     * {@code true}: stub every method of a mocked collaborator, including third-party/JDK.
     */
    public static final boolean STUB_THIRD_PARTY = false;

    /**
     * TWEAK-POINT: where emitted fixtures live. Flip HERE and re-run the Runner (IntelliJ Run
     * works; nothing else needed).
     *   "SHARED" = fixture files written once under testsupport/; each test class calls them.
     *   "NESTED" = the SAME fixture nested privately inside every detected test class - fully
     *              self-contained files, no shared test code.
     */
    public static final String FIXTURE_PLACEMENT = "SHARED";

    /**
     * TWEAK-POINT: what the emitted config fixture swaps in.
     *   "MOCK"   = a Mockito mock of the backing delegate; tests stub the values they care about.
     *   "SEEDED" = a REAL backing delegate populated with keys found in the scanned source, each
     *              set to the placeholder value below - adjust values per project as needed.
     */
    public static final String CONFIG_PAYLOAD = "MOCK";

    /** TWEAK-POINT: placeholder value under every discovered key in SEEDED mode. */
    public static final String SEEDED_PLACEHOLDER_VALUE = "5000";

    /** Call-chain recursion depth (ROADMAP tool 2). */
    public static final int CALL_CHAIN_MAX_DEPTH = 3;

    /** Cap edges emitted per method in call-chain. */
    public static final int CALL_CHAIN_MAX_EDGES = 40;

    private Config() {}

    /**
     * Optional method targets: {@code fqcn} or {@code fqcn#method} per line.
     * If missing/empty, falls back to all public methods of {@link #classesList()}.
     */
    public static Path methodsList() {
        return MODULE.resolve("testgen").resolve("methods.txt");
    }

    /** Method targets for call-chain / data-flow; defaults to classes.txt FQCNs. */
    public static List<String> readMethodTargets() throws Exception {
        Path list = methodsList();
        if (Files.isRegularFile(list)) {
            List<String> lines = Files.readAllLines(list).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (!lines.isEmpty()) {
                return lines;
            }
        }
        return readClasses();
    }

    /** {@code MODULE/testgen/classes.txt} */
    public static Path classesList() {
        return MODULE.resolve("testgen").resolve("classes.txt");
    }

    /** {@code MODULE/testgen/reports} */
    public static Path reportsDir() {
        return MODULE.resolve("testgen").resolve("reports");
    }

    public static Path mainSourceRoot() {
        return MAIN_SOURCES != null
                ? MAIN_SOURCES.toAbsolutePath().normalize()
                : MODULE.resolve("src").resolve("main").resolve("java");
    }

    public static Path testSourceRoot() {
        return TEST_SOURCES != null
                ? TEST_SOURCES.toAbsolutePath().normalize()
                : MODULE.resolve("src").resolve("test").resolve("java");
    }

    public static List<String> readClasses() throws Exception {
        Path list = classesList();
        if (!Files.isRegularFile(list)) {
            throw new IllegalStateException("classes list not found: " + list);
        }
        return Files.readAllLines(list).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}

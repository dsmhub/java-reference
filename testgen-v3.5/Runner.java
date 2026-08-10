package com.testgen;

import com.config.Config;
import com.testgen.graphs.CallChainScanner;
import com.testgen.graphs.DataFlowScanner;
import com.testgen.graphs.DepGraphMarker;
import com.testgen.graphs.DepGraphScanner;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;

/**
 * IntelliJ entry point. Mark {@code testgen/} as Test Sources, set Run WD to the
 * SUT module root, then Run. Comment out any call in {@link #main} to skip a step.
 */
public final class Runner {

    private Runner() {}

    public static void main(String[] args) throws Exception {
        // Parse modern Java (records/sealed/text-blocks) — JavaParser defaults to an old level.
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        if (Config.STRIP) {
            strip();   // reverse pass: remove the graph block; no scan, no generate
            return;
        }

        dependencies();
        callChain();
        dataFlow();
        embed();
        generate();
    }

    static void callChain() throws Exception {
        CallChainScanner.run();
    }

    static void dataFlow() throws Exception {
        DataFlowScanner.run();
    }

    static void dependencies() throws Exception {
        DepGraphScanner.run();
    }

    static void embed() throws Exception {
        DepGraphMarker.embed();
    }

    static void strip() throws Exception {
        DepGraphMarker.strip();
    }

    static void generate() throws Exception {
        TestSkeletonGenerator.run();
    }
}

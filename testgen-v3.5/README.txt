testgen v3.5 (RC) - changed and new files, verbatim copies.
Source: github.com/claudedsdhub/testgenlite-rc, branch dev/claude_260808_235809_agent7_fixflip, commit a719096 (2026-08-10).

CHANGED vs v3.4
    TestSkeletonGenerator.java      fixture wiring added to generation
    Runner.java                     imports for the new graphs package
    Config.java                     fixture switches (FIXTURE_PLACEMENT, CONFIG_PAYLOAD)
    graphs/CallChainScanner.java    local-variable type resolution fix

NEW in v3.5
    fixtures/SMCFixtureScanner.java             decides which classes need a delegate-swap fixture
    fixtures/FixtureGenerator.java              builds the fixture (shared testsupport or nested)
    fixtures/retrofit/InjectSMCFixture.java     adds the fixture wiring to existing tests
    fixtures/retrofit/SwitchSMCFixture.java     flips fixtures shared <-> nested
    tools/inject_smc_fixture.py                 Python hand tool, same job as InjectSMCFixture, trust-the-list

Prompt unchanged (v0.3). The other three graph scanners are identical to v3.4 apart from their new package.

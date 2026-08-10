package com.testgen;

import com.config.Config;
import com.testgen.fixtures.FixtureGenerator;
import com.testgen.fixtures.SMCFixtureScanner;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Batch skeleton generator:
 * - SUT arranged via `new` on a usable public ctor (TG-NOINSTSUT); Instancio only for the
 *   documented fallbacks (no/self-referencing public ctor, ctor fires project statics)
 * - Instancio for concrete collaborator/value args (unchanged v3.4 behaviour)
 * - Mockito.mock for interfaces; minimal stubbing for smoke act
 * - instance + static in same class; static-only SUTs get static tests only
 * - SUT contract methods: declared; if empty/thin → interfaces + immediate parent
 * - mirrors declared checked exceptions on generated @Test methods
 * - skip if *{suffix} already exists
 *
 * Array TypeName via {@link TypeName#get(java.lang.reflect.Type)} (javaPoetType).
 */
public final class TestSkeletonGenerator {

    private static final ClassName INSTANCIO = ClassName.get("org.instancio", "Instancio");
    private static final ClassName SELECT = ClassName.get("org.instancio", "Select");
    private static final ClassName MOCKITO = ClassName.get("org.mockito", "Mockito");
    private static final ClassName MATCHERS = ClassName.get("org.mockito", "ArgumentMatchers");
    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName BEFORE_EACH = ClassName.get("org.junit.jupiter.api", "BeforeEach");
    private static final ClassName AFTER_EACH = ClassName.get("org.junit.jupiter.api", "AfterEach");
    private static final ClassName DISABLED = ClassName.get("org.junit.jupiter.api", "Disabled");
    private static final ClassName ASSERTIONS = ClassName.get("org.junit.jupiter.api", "Assertions");
    private static final ClassName MOCKED_STATIC = ClassName.get("org.mockito", "MockedStatic");
    private static final ClassName MOCKED_CONSTRUCTION = ClassName.get("org.mockito", "MockedConstruction");

    private TestSkeletonGenerator() {}

    public static void run() throws Exception {
        Path testSourceRoot = Config.testSourceRoot();
        String suffix = Config.TEST_NAME_SUFFIX;
        List<String> classes = Config.readClasses();

        for (String fqcn : classes) {
            try {
                writeSkeleton(Class.forName(fqcn, false, TestSkeletonGenerator.class.getClassLoader()),
                        testSourceRoot, suffix);
            } catch (Throwable t) {
                String why = (t.getMessage() != null) ? t.getMessage() : t.toString();
                System.out.println("SKIP (unresolved " + t.getClass().getSimpleName() + ": " + why + "): " + fqcn);
            }
        }
    }

    static void writeSkeleton(Class<?> sut, Path testSourceRoot, String suffix) throws Exception {
        String testPackage = sut.getPackageName();
        String testSimpleName = sut.getSimpleName() + suffix;
        Path target = testSourceRoot
                .resolve(testPackage.replace('.', '/'))
                .resolve(testSimpleName + ".java");
        if (Files.exists(target)) {
            System.out.println("SKIP (exists): " + target);
            return;
        }

        boolean staticOnly = isStaticOnlyClass(sut);
        // BUG-2a (agent5 merge): an enum with an abstract method reports isAbstract()==true but IS
        // instantiable via its constants (Instancio yields a constant) — don't skip its instance methods.
        boolean abstractSut = !sut.isInterface() && !sut.isEnum()
                && java.lang.reflect.Modifier.isAbstract(sut.getModifiers());
        boolean genericSut = sut.getTypeParameters().length > 0;   // Instancio can't build Foo<T> without a type witness
        boolean ifaceSut = sut.isInterface();
        boolean uninstantiable = abstractSut || genericSut || ifaceSut;
        if (uninstantiable) {
            System.out.println("WARN " + (ifaceSut ? "interface" : genericSut ? "generic" : "abstract")
                    + " SUT — instance tests skipped (cannot instantiate reliably): " + sut.getName());
        }
        List<Method> instanceMethods =
                (staticOnly || uninstantiable) ? List.of() : contractInstanceMethods(sut);
        List<Method> staticMethods = contractStaticMethods(sut);
        boolean staticPrefix = !staticOnly && !instanceMethods.isEmpty();

        TypeSpec.Builder testClass = TypeSpec.classBuilder(testSimpleName)
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc(
                        "AUTO-GENERATED arrange skeleton for $L.\n"
                                + "Instancio SUT + locals per test; real assertions to be filled in after generation.\n",
                        sut.getName());

        // TG-BOILERDET: facades the SUT-set consumes get deterministic delegate-swap fixtures;
        // no facade detected -> every path below is byte-identical to the v3.4 output.
        List<SMCFixtureScanner.Facade> facades = SMCFixtureScanner.detect(sut);
        Set<Class<?>> facadeSuppress = SMCFixtureScanner.facadeClasses(facades);
        if (!facades.isEmpty()) {
            for (SMCFixtureScanner.Facade facade : facades) {
                ClassName fixtureName;
                if ("NESTED".equals(Config.FIXTURE_PLACEMENT)) {
                    // TWEAK-POINT (FIXTURE_PLACEMENT=NESTED): same fixture, nested inside this test class.
                    testClass.addType(FixtureGenerator.nestedType(facade));
                    fixtureName = ClassName.get(testPackage, testSimpleName,
                            FixtureGenerator.fixtureSimpleName(facade));
                } else {
                    fixtureName = FixtureGenerator.write(facade, testSourceRoot);
                }
                testClass.addField(FieldSpec.builder(fixtureName, fixtureFieldName(facade),
                                Modifier.PRIVATE, Modifier.FINAL)
                        .initializer("new $T()", fixtureName)
                        .build());
            }
        }

        if (!instanceMethods.isEmpty()) {
            testClass.addField(FieldSpec.builder(ClassName.get(sut), "sut", Modifier.PRIVATE).build());
        }
        if (!facades.isEmpty()) {
            testClass.addMethod(boilerplateSetup(sut, facades, !instanceMethods.isEmpty()));
            testClass.addMethod(boilerplateTearDown(facades));
        } else if (!instanceMethods.isEmpty()) {
            testClass.addMethod(setUpMethod(sut));
        }
        if (!instanceMethods.isEmpty()) {
            Set<String> usedNames = new HashSet<>();
            for (Method m : instanceMethods) {
                testClass.addMethod(instanceTest(sut, m, usedNames, facadeSuppress));
            }
        }
        if (!staticMethods.isEmpty()) {
            Set<String> usedNames = instanceMethods.stream()
                    .map(TestSkeletonGenerator::safeName)
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new));
            for (Method m : staticMethods) {
                testClass.addMethod(staticTest(sut, m, staticPrefix, usedNames, facadeSuppress));
            }
        }
        if (instanceMethods.isEmpty() && staticMethods.isEmpty()) {
            System.out.println("WARN no public methods: " + sut.getName());
            testClass.addJavadoc("No public methods discovered.\n");
        }

        JavaFile.builder(testPackage, testClass.build())
                .addFileComment("AUTO-GENERATED by testgen TestSkeletonGenerator")
                .build()
                .writeTo(testSourceRoot);

        System.out.println("Wrote " + testPackage + "." + testSimpleName
                + " (instance=" + instanceMethods.size() + ", static=" + staticMethods.size() + ")");
    }

    private static MethodSpec setUpMethod(Class<?> sut) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("setUp")
                .addAnnotation(BEFORE_EACH)
                .addModifiers(Modifier.PUBLIC)
                .addException(Exception.class);   // stubbed collaborator methods may throw checked exceptions
        arrangeInto(b, sut);
        return b.build();
    }

    /**
     * TG-NOINSTSUT: the public constructor chosen for arranging the SUT via {@code new}, or null
     * when Instancio must stay (no public ctor / self-referencing param / ctor-or-field-initializer
     * fires project statics that {@code new} would execute for real).
     * Choice among usable ctors: max parameter count (DI-shaped), tie-broken deterministically on
     * the parameter-type signature string.
     */
    private static java.lang.reflect.Constructor<?> noInstSutCtor(Class<?> sut) {
        List<java.lang.reflect.Constructor<?>> usable = new ArrayList<>();
        for (java.lang.reflect.Constructor<?> c : sut.getConstructors()) {
            if (!ctorSelfReferencing(sut, c) && !ctorFiresProjectStatics(sut, c)) {
                usable.add(c);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        usable.sort(java.util.Comparator
                .comparingInt((java.lang.reflect.Constructor<?> c) -> c.getParameterCount()).reversed()
                .thenComparing(TestSkeletonGenerator::ctorSignature));
        return usable.get(0);
    }

    /** A ctor taking the SUT's own type cannot be arranged without recursing — Instancio stays. */
    private static boolean ctorSelfReferencing(Class<?> sut, java.lang.reflect.Constructor<?> c) {
        for (Class<?> pt : c.getParameterTypes()) {
            if (pt.equals(sut)) {
                return true;
            }
        }
        return false;
    }

    /** Statics scan of ONE class's ctors (paramCount -1 = all) + instance-field initializers. */
    private static boolean classFiresProjectStatics(Class<?> owner, Class<?> sut, int paramCount) {
        try {
            Path src = Config.mainSourceRoot().resolve(owner.getName().replace('.', '/') + ".java");
            if (!Files.isRegularFile(src)) {
                return false;
            }
            CompilationUnit cu = StaticJavaParser.parse(src);
            String pkg = owner.getPackageName();

            List<com.github.javaparser.ast.Node> scopes = new ArrayList<>();
            for (com.github.javaparser.ast.body.ConstructorDeclaration cd
                    : cu.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class)) {
                if (cd.getNameAsString().equals(owner.getSimpleName())
                        && (paramCount < 0 || cd.getParameters().size() == paramCount)) {
                    scopes.add(cd);
                }
            }
            for (com.github.javaparser.ast.body.FieldDeclaration fd
                    : cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)) {
                if (!fd.isStatic()) {
                    scopes.add(fd);   // instance-field initializers also run on `new`
                }
            }
            for (com.github.javaparser.ast.body.InitializerDeclaration id
                    : cu.findAll(com.github.javaparser.ast.body.InitializerDeclaration.class)) {
                if (!id.isStatic()) {
                    scopes.add(id);   // instance initializer blocks also run on `new` (a11 break #3)
                }
            }

            // Unscoped calls in a ctor/init are the SUT's own methods; their bodies run on `new`
            // too, so walk them one level (a11 break #2). Depth 1 over-retains; it never misses
            // the direct case and the differential backstops deeper chains.
            List<com.github.javaparser.ast.Node> expanded = new ArrayList<>(scopes);
            for (com.github.javaparser.ast.Node scope : scopes) {
                for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
                    if (call.getScope().isEmpty()) {
                        for (com.github.javaparser.ast.body.MethodDeclaration md
                                : cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {
                            if (md.getNameAsString().equals(call.getNameAsString())) {
                                expanded.add(md);
                            }
                        }
                    }
                }
            }
            scopes = expanded;

            for (com.github.javaparser.ast.Node scope : scopes) {
                // `new ProjectType()` runs that type's ctor for real (a11 break #1): retain.
                for (ObjectCreationExpr oce : scope.findAll(ObjectCreationExpr.class)) {
                    String typeName = oce.getType().getNameAsString();
                    Class<?> created = typeName.contains(".") ? tryLoad(typeName)
                            : resolveSimple(cu, pkg, typeName);
                    if (created != null && isProjectType(created) && !created.equals(sut)) {
                        return true;
                    }
                }
                for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
                    if (call.getScope().isPresent()) {
                        com.github.javaparser.ast.expr.Expression s = call.getScope().get();
                        String q = null;
                        if (s instanceof NameExpr ne && !ne.getNameAsString().isEmpty()
                                && Character.isUpperCase(ne.getNameAsString().charAt(0))) {
                            q = ne.getNameAsString();
                        } else if (s instanceof com.github.javaparser.ast.expr.FieldAccessExpr fae) {
                            q = fae.toString();
                        }
                        if (q != null) {
                            Class<?> c = q.contains(".") ? tryLoad(q) : resolveSimple(cu, pkg, q);
                            if (c != null && isProjectType(c) && !c.equals(sut)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false;   // unparseable source ⇒ no evidence of statics; differential is the backstop
        }
    }

    /** Why Instancio stays for this SUT — emitted into the generated setUp so the reason ships. */
    private static String instancioRetainedReason(Class<?> sut) {
        if (sut.getConstructors().length == 0) {
            return "no public constructor";
        }
        boolean allSelfRef = true;
        for (java.lang.reflect.Constructor<?> c : sut.getConstructors()) {
            if (!ctorSelfReferencing(sut, c)) {
                allSelfRef = false;
                break;
            }
        }
        if (allSelfRef) {
            return "every public constructor takes the SUT's own type (self-referencing)";
        }
        return "constructor or field initializer invokes project statics (new would fire them for real)";
    }

    /** TG-NOINSTSUT: arrange each ctor param with the EXISTING arg machinery, then {@code new} the SUT. */
    private static void ctorArrangeInto(MethodSpec.Builder b, Class<?> sut,
            java.lang.reflect.Constructor<?> ctor) {
        List<MockRef> mocks = new ArrayList<>();
        List<String> args = new ArrayList<>();
        java.lang.reflect.Type[] gpts = ctor.getGenericParameterTypes();
        Parameter[] ps = ctor.getParameters();

        for (int i = 0; i < ps.length; i++) {
            Class<?> ptype = resolveParamType(sut, gpts[i], ps[i].getType());
            String var = "c" + i + "_" + simpleParamName(ps[i]);
            b.addCode(arrangeLine(ptype, var, mocks));
            args.add(var);
        }
        b.addStatement("sut = new $T($L)", ClassName.get(sut), String.join(", ", args));

        for (MockRef mock : mocks) {
            FieldMockRef fm = soleFieldOfType(sut, mock.type(), mock.varName());
            if (fm != null) {   // exactly one matching SUT field ⇒ reuse the richer field-aware stubs
                b.addCode(minimalStubs(mock, fieldBindings(sut, fm)));
                b.addCode(factoryStubs(sut, mock.type(), fm.fieldName(), mock.varName()));
            } else {
                b.addCode(minimalStubs(mock));
            }
        }
    }

    /** The single SUT instance field of exactly this type, or null when zero/ambiguous. */
    private static FieldMockRef soleFieldOfType(Class<?> sut, Class<?> type, String varName) {
        Field match = null;
        for (Field f : instanceFields(sut)) {
            if (f.getType().equals(type)) {
                if (match != null) {
                    return null;   // ambiguous — plain stubs are the safe choice
                }
                match = f;
            }
        }
        return (match == null) ? null
                : new FieldMockRef(type, varName, match.getDeclaringClass(), match.getName(), false);
    }

    /**
     * True when the ctor body or an instance-field initializer statically calls a project class —
     * {@code new} would fire those statics for real (Instancio bypasses construction), so the
     * Instancio arrangement is kept for safety. The SUPERCLASS chain is walked too (super-ctors
     * and parent field initializers also run on {@code new}); all parent ctors are scanned, which
     * over-retains rather than under-retains. Source unavailable ⇒ false (same policy as
     * {@link #analyzeStaticsAndNew}); the differential run is the backstop.
     */
    private static boolean ctorFiresProjectStatics(Class<?> sut, java.lang.reflect.Constructor<?> ctor) {
        if (classFiresProjectStatics(sut, sut, ctor.getParameterCount())) {
            return true;
        }
        for (Class<?> parent = sut.getSuperclass();
                parent != null && parent != Object.class && isProjectType(parent);
                parent = parent.getSuperclass()) {
            if (classFiresProjectStatics(parent, sut, -1)) {   // -1: every ctor of the parent
                return true;
            }
        }
        return false;
    }

    /** Deterministic tie-break key: the ctor's parameter-type signature string. */
    private static String ctorSignature(java.lang.reflect.Constructor<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> pt : c.getParameterTypes()) {
            sb.append(pt.getName()).append(',');
        }
        return sb.toString();
    }

    /** Fixture lifecycle: install first in setup, then the standard arrange; restore in tearDown. */
    private static MethodSpec boilerplateSetup(Class<?> sut, List<SMCFixtureScanner.Facade> facades,
            boolean arrangeSut) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("setup")
                .addAnnotation(BEFORE_EACH)
                .addModifiers(Modifier.PUBLIC)
                .addException(Exception.class);
        for (SMCFixtureScanner.Facade facade : facades) {
            b.addStatement("$L.install()", fixtureFieldName(facade));
        }
        if (arrangeSut) {
            arrangeInto(b, sut);
        }
        return b.build();
    }

    /** Restore is mandatory — the swapped delegates are JVM-global state (lab play). */
    private static MethodSpec boilerplateTearDown(List<SMCFixtureScanner.Facade> facades) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("tearDown")
                .addAnnotation(AFTER_EACH)
                .addModifiers(Modifier.PUBLIC)
                .addException(Exception.class);
        for (int i = facades.size() - 1; i >= 0; i--) {
            b.addStatement("$L.restore()", fixtureFieldName(facades.get(i)));
        }
        return b.build();
    }

    private static String fixtureFieldName(SMCFixtureScanner.Facade facade) {
        String simple = FixtureGenerator.fixtureSimpleName(facade);
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static void arrangeInto(MethodSpec.Builder b, Class<?> sut) {
        /*
         * TWEAK-POINT (TG-NOINSTSUT): the SUT itself is arranged via `new` when a public ctor is
         * usable; Instancio only for the documented fallbacks (reason emitted into the test).
         * Ctor choice: noInstSutCtor(). Safety scan: classFiresProjectStatics().
         */
        java.lang.reflect.Constructor<?> ctor = noInstSutCtor(sut);
        if (ctor != null) {
            ctorArrangeInto(b, sut, ctor);
            return;
        }
        b.addComment("Instancio retained for SUT construction: $L", instancioRetainedReason(sut));

        List<FieldMockRef> fieldMocks = new ArrayList<>();
        for (Field field : instanceFields(sut)) {
            Class<?> ft = field.getType();
            Class<?> coll = collectionImpl(ft);
            Class<?> sealedImpl = sealedConcrete(ft);   // sealed types are unmockable → use a permitted impl
            boolean abstractCls = !ft.isArray() && !ft.isInterface() && !ft.isPrimitive()
                    && java.lang.reflect.Modifier.isAbstract(ft.getModifiers());   // primitives report isAbstract too
            if (coll == null && sealedImpl == null && !ft.isInterface() && !abstractCls
                    && !(ft.getTypeParameters().length > 0 && !ft.isArray() && ft != Class.class)) {
                continue;   // mock interface/abstract + generic-concrete fields (Instancio can't build them); Class is unmockable
            }
            boolean realValue = coll != null || sealedImpl != null;   // real object, not a mock → no stubbing
            String var = field.getName() + (realValue ? "Val" : "Mock");
            fieldMocks.add(new FieldMockRef(ft, var, field.getDeclaringClass(), field.getName(), realValue));
            if (coll != null) {   // real empty collection, not a mock (mock iterator() == null → NPE when iterated)
                b.addStatement("$T $L = new $T<>()", ClassName.get(ft), var, ClassName.get(coll));
            } else if (sealedImpl != null) {   // permitted concrete impl of the sealed type
                b.addStatement("$T $L = $T.create($T.class)", ClassName.get(ft), var, INSTANCIO, ClassName.get(sealedImpl));
            } else {
                b.addStatement("$T $L = $T.mock($T.class)",
                        ClassName.get(ft), var, MOCKITO, ClassName.get(ft));
            }
        }

        if (fieldMocks.isEmpty()) {
            b.addStatement("sut = $T.create($T.class)", INSTANCIO, ClassName.get(sut));
        } else {
            CodeBlock.Builder chain = CodeBlock.builder().add("sut = $T.of($T.class)",
                    INSTANCIO, ClassName.get(sut));
            for (FieldMockRef mock : fieldMocks) {
                chain.add("\n    .set($T.field($T.class, $S), $L)",
                        SELECT, ClassName.get(mock.declaringClass), mock.fieldName, mock.varName);
            }
            chain.add("\n    .create();\n");
            b.addCode(chain.build());
        }

        for (FieldMockRef mock : fieldMocks) {
            if (mock.skipStub()) continue;   // real collection/sealed value, not a mock — nothing to stub
            b.addCode(minimalStubs(new MockRef(mock.type(), mock.varName()), fieldBindings(sut, mock)));
            b.addCode(factoryStubs(sut, mock.type(), mock.fieldName(), mock.varName()));   // call-site Class<T> binds
        }
    }

    private static MethodSpec instanceTest(Class<?> sut, Method method, Set<String> usedNames,
            Set<Class<?>> facadeSuppress) {
        MethodSpec.Builder b = MethodSpec.methodBuilder(uniqueName(safeName(method), usedNames))
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(TEST)
                .addException(Exception.class);   // stubbed collaborator methods may throw checked exceptions
        addDeclaredExceptions(method, b);

        String disable = disableReason(method);
        if (disable != null) return disabledTest(b, disable);

        SutStatics st = suppressFacades(analyzeStaticsAndNew(sut, method), facadeSuppress);
        boolean scope = st != null && (!st.staticTargets().isEmpty() || !st.constructedTypes().isEmpty());
        if (scope) {
            b.beginControlFlow("try ($L)", mockScopeResources(st));
            emitStaticStubs(b, st, sut);
        }

        List<MockRef> mocks = new ArrayList<>();
        List<String> methodArgNames = new ArrayList<>();
        java.lang.reflect.Type[] gpts = method.getGenericParameterTypes();
        Parameter[] ps = method.getParameters();
        for (int i = 0; i < ps.length; i++) {
            Class<?> ptype = resolveParamType(sut, gpts[i], ps[i].getType());
            String var = "a" + i + "_" + simpleParamName(ps[i]);
            b.addCode(arrangeLine(ptype, var, mocks));
            methodArgNames.add(var);
        }

        for (MockRef mock : mocks) {
            b.addCode(minimalStubs(mock));
        }

        String callArgs = String.join(", ", methodArgNames);
        if (method.getReturnType() == void.class) {
            b.addStatement("sut.$L($L)", method.getName(), callArgs);
            b.addStatement("$T.assertNotNull(sut)", ASSERTIONS);
        } else if (method.getReturnType().isPrimitive()) {
            b.addStatement("sut.$L($L)", method.getName(), callArgs);
            b.addStatement("$T.assertTrue(true)", ASSERTIONS);
        } else {
            b.addStatement("$T result = sut.$L($L)",
                    TypeName.get(method.getReturnType()), method.getName(), callArgs);
            b.addComment("TODO: assert on result (a null return may be valid — arrange skeleton)");
        }
        if (scope) {
            b.endControlFlow();
        }
        return b.build();
    }

    private static MethodSpec staticTest(Class<?> sut, Method method, boolean prefix, Set<String> usedNames,
            Set<Class<?>> facadeSuppress) {
        String base = prefix ? "static_" + safeName(method) : safeName(method);
        MethodSpec.Builder b = MethodSpec.methodBuilder(uniqueName(base, usedNames))
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(TEST)
                .addException(Exception.class);   // stubbed collaborator methods may throw checked exceptions
        addDeclaredExceptions(method, b);

        String disable = disableReason(method);
        if (disable != null) return disabledTest(b, disable);

        SutStatics st = suppressFacades(analyzeStaticsAndNew(sut, method), facadeSuppress);
        boolean scope = st != null && (!st.staticTargets().isEmpty() || !st.constructedTypes().isEmpty());
        if (scope) {
            b.beginControlFlow("try ($L)", mockScopeResources(st));
            emitStaticStubs(b, st, sut);
        }

        List<MockRef> mocks = new ArrayList<>();
        List<String> methodArgNames = new ArrayList<>();
        java.lang.reflect.Type[] gpts = method.getGenericParameterTypes();
        Parameter[] ps = method.getParameters();
        for (int i = 0; i < ps.length; i++) {
            Class<?> ptype = resolveParamType(sut, gpts[i], ps[i].getType());
            String var = "a" + i + "_" + simpleParamName(ps[i]);
            b.addCode(arrangeLine(ptype, var, mocks));
            methodArgNames.add(var);
        }
        for (MockRef mock : mocks) {
            b.addCode(minimalStubs(mock));
        }

        String callArgs = String.join(", ", methodArgNames);
        ClassName sutCn = ClassName.get(sut);
        if (method.getReturnType() == void.class) {
            b.addStatement("$T.$L($L)", sutCn, method.getName(), callArgs);
            b.addStatement("$T.assertTrue(true)", ASSERTIONS);
        } else if (method.getReturnType().isPrimitive()) {
            b.addStatement("$T.$L($L)", sutCn, method.getName(), callArgs);
            b.addStatement("$T.assertTrue(true)", ASSERTIONS);
        } else {
            b.addStatement("$T result = $T.$L($L)",
                    TypeName.get(method.getReturnType()), sutCn, method.getName(), callArgs);
            b.addComment("TODO: assert on result (a null return may be valid — arrange skeleton)");
        }
        if (scope) {
            b.endControlFlow();
        }
        return b.build();
    }

    private static List<Field> instanceFields(Class<?> sut) {
        List<Field> fields = new ArrayList<>();
        Class<?> cur = sut;
        while (cur != null && cur != Object.class) {
            // Skip JDK frames (e.g. Throwable.suppressedExceptions) — not SUT collaborators.
            if (isJdkType(cur)) {
                cur = cur.getSuperclass();
                continue;
            }
            for (Field field : cur.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
            cur = cur.getSuperclass();
        }
        return fields;
    }

    private static boolean isJdkType(Class<?> type) {
        Package p = type.getPackage();
        if (p == null) {
            return false;
        }
        String n = p.getName();
        return n.equals("java") || n.startsWith("java.")
                || n.equals("javax") || n.startsWith("javax.")
                || n.equals("jdk") || n.startsWith("jdk.");
    }

    /** True if the class's top-level source exists under the main source root (project type, not JDK/third-party). */
    private static boolean isProjectType(Class<?> type) {
        if (type == null) {
            return false;
        }
        Class<?> top = type;
        while (top.getEnclosingClass() != null) {
            top = top.getEnclosingClass();
        }
        return Files.exists(Config.mainSourceRoot().resolve(top.getName().replace('.', '/') + ".java"));
    }

    /** Resolve a parameter's generic type against the SUT's superclass parameterization, so a type
     *  variable from a generic parent (e.g. T extends ClaimsHolder) becomes the concrete type the
     *  call site requires (e.g. HeaderClaimsHolder) rather than the erased bound. Falls back to erased. */
    /** Honest skip: a compiling @Disabled test with the reason, for methods the skeleton can't arrange. */
    private static MethodSpec disabledTest(MethodSpec.Builder b, String reason) {
        b.addAnnotation(AnnotationSpec.builder(DISABLED).addMember("value", "$S", reason).build());
        b.addStatement("$T.assertTrue(true)", ASSERTIONS);
        return b.build();
    }

    private static Class<?> resolveParamType(Class<?> sut, java.lang.reflect.Type generic, Class<?> erased) {
        try {
            Class<?> multi = multiBoundConcrete(generic);
            if (multi != null) return multi;   // <T extends Number & Comparable<T>> -> Integer (satisfies all)
            Class<?> r = toClass(resolveType(generic, typeBindings(sut)));
            return (r != null && r != Object.class) ? r : erased;
        } catch (Throwable t) {
            return erased;
        }
    }

    /** For a type variable with an INTERSECTION bound whose first bound alone can't be denoted as an
     *  argument (Number isn't Comparable), find a concrete type assignable to ALL bounds so the call
     *  type-checks. Returns null when the first bound already satisfies the rest (normal path) or when
     *  no common concrete exists (caller then @Disables the method). */
    private static Class<?> multiBoundConcrete(java.lang.reflect.Type generic) {
        if (!(generic instanceof java.lang.reflect.TypeVariable<?> tv)) return null;
        java.lang.reflect.Type[] bounds = tv.getBounds();
        if (bounds.length <= 1) return null;
        Class<?> first = toClass(bounds[0]);
        boolean firstSatisfies = true;
        for (int i = 1; i < bounds.length; i++) {
            if (!toClass(bounds[i]).isAssignableFrom(first)) { firstSatisfies = false; break; }
        }
        if (firstSatisfies) return null;   // first bound denotable on its own — normal path handles it
        for (Class<?> c : new Class<?>[]{Integer.class, Long.class, Double.class, String.class, Boolean.class}) {
            boolean ok = true;
            for (java.lang.reflect.Type b : bounds) if (!toClass(b).isAssignableFrom(c)) { ok = false; break; }
            if (ok) return c;
        }
        return null;   // no common concrete -> method gets @Disabled
    }

    /** Non-null reason when a method can't be safely arranged: an intersection-bound type-var param
     *  with no denotable/concrete satisfying type. The test is emitted @Disabled (honest skip) rather
     *  than as code that won't compile. */
    private static String disableReason(Method m) {
        for (java.lang.reflect.Type gt : m.getGenericParameterTypes()) {
            if (gt instanceof java.lang.reflect.TypeVariable<?> tv && tv.getBounds().length > 1) {
                Class<?> first = toClass(tv.getBounds()[0]);
                boolean firstSatisfies = true;
                for (int i = 1; i < tv.getBounds().length; i++) {
                    if (!toClass(tv.getBounds()[i]).isAssignableFrom(first)) { firstSatisfies = false; break; }
                }
                if (!firstSatisfies && multiBoundConcrete(gt) == null) {
                    return "testgen: cannot arrange intersection-bounded type parameter <" + tv.getName() + ">";
                }
            }
        }
        return null;
    }

    private static java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> typeBindings(Class<?> sut) {
        java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> map = new java.util.HashMap<>();
        collectBindings(sut, map);
        return map;
    }

    /** Collect type-var bindings from the SUT's generic superclasses AND generic interfaces
     *  (e.g. Comparable&lt;Priority&gt; -&gt; T=Priority), recursively. */
    private static void collectBindings(Class<?> c,
            java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> map) {
        if (c == null || c == Object.class) {
            return;
        }
        bindArgs(c.getGenericSuperclass(), map);
        collectBindings(c.getSuperclass(), map);
        for (java.lang.reflect.Type it : c.getGenericInterfaces()) {
            bindArgs(it, map);
            if (it instanceof java.lang.reflect.ParameterizedType ipt) {
                collectBindings((Class<?>) ipt.getRawType(), map);
            } else if (it instanceof Class<?> ic) {
                collectBindings(ic, map);
            }
        }
    }

    /** Bindings for a collaborator field: SUT-level bindings plus the field's own type args
     *  (store:Store&lt;String&gt; -> Store's T -> String), so its methods' T returns resolve concretely. */
    private static java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> fieldBindings(
            Class<?> sut, FieldMockRef mock) {
        java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> map = typeBindings(sut);
        try {
            bindArgs(mock.declaringClass().getDeclaredField(mock.fieldName()).getGenericType(), map);
        } catch (NoSuchFieldException ignored) {
            // field not reflectively found (shouldn't happen) — fall back to SUT-level bindings only
        }
        return map;
    }

    private static void bindArgs(java.lang.reflect.Type t,
            java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> map) {
        if (t instanceof java.lang.reflect.ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            java.lang.reflect.TypeVariable<?>[] vars = raw.getTypeParameters();
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            for (int i = 0; i < vars.length && i < args.length; i++) {
                map.put(vars[i], resolveType(args[i], map));
            }
        }
    }

    private static java.lang.reflect.Type resolveType(java.lang.reflect.Type t,
            java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> map) {
        if (t instanceof java.lang.reflect.TypeVariable<?> tv) {
            java.lang.reflect.Type bound = map.get(tv);
            if (bound != null && bound != tv) {
                return resolveType(bound, map);
            }
            java.lang.reflect.Type[] bounds = tv.getBounds();
            return bounds.length > 0 ? bounds[0] : Object.class;
        }
        return t;
    }

    /** A concrete Class for a collaborator method's generic return, resolving type variables (and T[])
     *  via the collaborator's bindings; null if it can't be resolved to a concrete non-Object type. */
    private static Class<?> resolveReturnClass(java.lang.reflect.Type gret,
            java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> bindings) {
        if (bindings.isEmpty()) return null;
        if (gret instanceof java.lang.reflect.TypeVariable<?>) {
            Class<?> c = toClass(resolveType(gret, bindings));
            return (c != null && c != Object.class) ? c : null;
        }
        if (gret instanceof java.lang.reflect.GenericArrayType gat
                && gat.getGenericComponentType() instanceof java.lang.reflect.TypeVariable<?> comp) {
            Class<?> cc = toClass(resolveType(comp, bindings));
            if (cc != null && cc != Object.class) {
                return java.lang.reflect.Array.newInstance(cc, 0).getClass();   // e.g. String[].class
            }
        }
        return null;
    }

    /** True if the generic return is a type variable (or an array of one) — i.e. erased to Object/Object[]. */
    private static boolean isTypeVarReturn(java.lang.reflect.Type gret) {
        if (gret instanceof java.lang.reflect.TypeVariable<?>) return true;
        if (gret instanceof java.lang.reflect.GenericArrayType gat) return isTypeVarReturn(gat.getGenericComponentType());
        return false;
    }

    private static Class<?> toClass(java.lang.reflect.Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof java.lang.reflect.ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        if (t instanceof java.lang.reflect.GenericArrayType) {
            return Object[].class;
        }
        if (t instanceof java.lang.reflect.TypeVariable<?> tv) {
            java.lang.reflect.Type[] b = tv.getBounds();
            return b.length > 0 ? toClass(b[0]) : Object.class;
        }
        return Object.class;
    }

    private static boolean isStaticOnlyClass(Class<?> sut) {
        return filterInstance(sut.getDeclaredMethods()).isEmpty()
                && !filterStatic(sut.getDeclaredMethods()).isEmpty();
    }

    /** Prefer TypeName.get so arrays (and generics) render correctly. */
    private static TypeName javaPoetType(Class<?> type) {
        return TypeName.get(type);
    }

    /** Concrete impl for a JDK collection/map interface, else null. A mock collection returns
     *  null from iterator()/entrySet() → SUTs that iterate the arg NPE; a real empty one runs. */
    private static Class<?> collectionImpl(Class<?> t) {
        if (!t.isInterface()) return null;
        if (t == java.util.NavigableMap.class || t == java.util.SortedMap.class) return java.util.TreeMap.class;
        if (t == java.util.Map.class) return java.util.HashMap.class;
        if (t == java.util.NavigableSet.class || t == java.util.SortedSet.class) return java.util.TreeSet.class;
        if (t == java.util.Set.class) return java.util.HashSet.class;
        if (t == java.util.Queue.class || t == java.util.Deque.class) return java.util.ArrayDeque.class;
        if (t == java.util.List.class || t == java.util.Collection.class || t == Iterable.class)
            return java.util.ArrayList.class;
        return null;
    }

    /** A permitted, instantiable concrete subtype of a sealed type (recursing through sealed subtiers),
     *  else null. Sealed types are unmockable (Mockito refuses) — build a permitted impl instead. */
    private static Class<?> sealedConcrete(Class<?> t) {
        if (t == null || !t.isSealed()) return null;
        for (Class<?> sub : t.getPermittedSubclasses()) {
            if (sub.isSealed()) {
                Class<?> deeper = sealedConcrete(sub);
                if (deeper != null) return deeper;
            } else if (!sub.isInterface() && !java.lang.reflect.Modifier.isAbstract(sub.getModifiers())) {
                return sub;   // concrete record/class permitted by the seal
            }
        }
        return null;
    }

    private static CodeBlock arrangeLine(Class<?> type, String varName, List<MockRef> mocks) {
        Class<?> sealedImpl = sealedConcrete(type);
        if (sealedImpl != null) {   // sealed param -> a permitted concrete impl (sealed types are unmockable)
            return arrangeConcrete(sealedImpl, varName, mocks);
        }
        Class<?> coll = collectionImpl(type);
        if (coll != null) {   // real empty collection, not a mock (mock iterator() == null → NPE)
            return CodeBlock.of("$T $L = new $T<>();\n", ClassName.get(type), varName, ClassName.get(coll));
        }
        if (type.isInterface() || (!type.isArray() && !type.isPrimitive() && type != Class.class
                && (java.lang.reflect.Modifier.isAbstract(type.getModifiers())
                        || type.getTypeParameters().length > 0))) {   // generic concrete: Instancio can't build raw
            mocks.add(new MockRef(type, varName));
            return CodeBlock.of("$T $L = $T.mock($T.class);\n",
                    ClassName.get(type), varName, MOCKITO, ClassName.get(type));
        }
        if (type == Class.class) {
            return CodeBlock.of("$T<?> $L = $T.class;\n",
                    ClassName.get(Class.class), varName, ClassName.get(String.class));
        }
        if (type == java.math.BigDecimal.class) {
            return CodeBlock.of("$T $L = $T.ZERO;\n",
                    ClassName.get(java.math.BigDecimal.class), varName,
                    ClassName.get(java.math.BigDecimal.class));
        }
        if (type.isPrimitive()) {
            Class<?> boxed = box(type);
            return CodeBlock.of("$T $L = $T.create($T.class);\n",
                    ClassName.get(boxed), varName, INSTANCIO, ClassName.get(boxed));
        }
        return arrangeConcrete(type, varName, mocks);
    }

    private static CodeBlock arrangeConcrete(Class<?> type, String varName, List<MockRef> mocks) {
        List<Field> ifaceFields = interfaceInstanceFields(type);
        if (ifaceFields.isEmpty()) {
            TypeName tn = javaPoetType(type);
            return CodeBlock.of("$T $L = $T.create($T.class);\n", tn, varName, INSTANCIO, tn);
        }
        CodeBlock.Builder block = CodeBlock.builder();
        for (Field field : ifaceFields) {
            String mockVar = varName + "_" + field.getName() + "Mock";
            mocks.add(new MockRef(field.getType(), mockVar));
            block.addStatement("$T $L = $T.mock($T.class)",
                    ClassName.get(field.getType()), mockVar, MOCKITO, ClassName.get(field.getType()));
        }
        CodeBlock.Builder chain = CodeBlock.builder().add("$T $L = $T.of($T.class)",
                ClassName.get(type), varName, INSTANCIO, ClassName.get(type));
        for (Field field : ifaceFields) {
            String mockVar = varName + "_" + field.getName() + "Mock";
            chain.add("\n    .set($T.field($T.class, $S), $L)",
                    SELECT, ClassName.get(field.getDeclaringClass()), field.getName(), mockVar);
        }
        chain.add("\n    .create();\n");
        block.add(chain.build());
        return block.build();
    }

    private static List<Field> interfaceInstanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field field : instanceFields(type)) {
            Class<?> ft = field.getType();
            boolean abstractCls = !ft.isArray() && !ft.isInterface() && !ft.isPrimitive()
                    && java.lang.reflect.Modifier.isAbstract(ft.getModifiers());   // primitives report isAbstract too
            if (ft.isInterface() || abstractCls
                    || (ft.getTypeParameters().length > 0 && !ft.isArray() && ft != Class.class)) {
                out.add(field);
            }
        }
        return out;
    }

    private static void addDeclaredExceptions(Method method, MethodSpec.Builder testMethod) {
        for (Class<?> ex : method.getExceptionTypes()) {
            testMethod.addException(TypeName.get(ex));
        }
    }

    private static CodeBlock minimalStubs(MockRef mock) {
        return minimalStubs(mock, java.util.Map.of());
    }

    /** Call-site-aware stubs for a collaborator's {@code <T> T m(Class<T>)} factory methods. The SUT
     *  passes a concrete {@code X.class} that the generic any()-matcher stub can't bind to T (so #37
     *  returns null -> NPE if the SUT derefs). Here we read the SUT source, find {@code field.m(X.class)}
     *  calls, and emit {@code when(mock.m(X.class)).thenReturn(<value of X>)} so T resolves per call. */
    private static CodeBlock factoryStubs(Class<?> sut, Class<?> collab, String fieldName, String mockVar) {
        CodeBlock.Builder out = CodeBlock.builder();
        try {
            Path src = Config.mainSourceRoot().resolve(sut.getName().replace('.', '/') + ".java");
            if (!Files.isRegularFile(src)) return out.build();
            CompilationUnit cu = StaticJavaParser.parse(src);
            String pkg = sut.getPackageName();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (call.getScope().isEmpty()) continue;
                com.github.javaparser.ast.expr.Expression scope = call.getScope().get();
                String scopeName = scope instanceof NameExpr ne ? ne.getNameAsString()
                        : scope instanceof com.github.javaparser.ast.expr.FieldAccessExpr fae ? fae.getNameAsString()
                        : null;
                if (!fieldName.equals(scopeName)) continue;
                int[] pos = {-1};
                Class<?> x = classArg(call, cu, pkg, pos);   // the single X.class arg + its position
                if (x == null) continue;
                Method fm = factoryMethod(collab, call.getNameAsString(), call.getArguments().size(), pos[0]);
                if (fm == null || !seen.add(call.getNameAsString() + "|" + pos[0] + "|" + x.getName())) continue;
                out.addStatement("$T.when($L.$L($L)).thenReturn($L)",
                        MOCKITO, mockVar, call.getNameAsString(), factoryMatchers(fm, pos[0], x), stubValue(x));
            }
        } catch (Throwable ignored) {
            // best-effort: on any parse/resolve failure fall back to the generic stub already emitted
        }
        return out.build();
    }

    /** If the call has exactly one {@code X.class} argument, resolve X and record its position; else null. */
    private static Class<?> classArg(MethodCallExpr call, CompilationUnit cu, String pkg, int[] posOut) {
        Class<?> x = null;
        for (int i = 0; i < call.getArguments().size(); i++) {
            if (call.getArgument(i) instanceof com.github.javaparser.ast.expr.ClassExpr ce) {
                if (posOut[0] >= 0) return null;   // more than one Class arg — ambiguous, skip
                posOut[0] = i;
                x = resolveSimple(cu, pkg, ce.getType().asString());
            }
        }
        return x;
    }

    /** Matchers for a factory call: eq(X.class) at the Class position, typed any() elsewhere. */
    private static CodeBlock factoryMatchers(Method fm, int classPos, Class<?> x) {
        Class<?>[] ps = fm.getParameterTypes();
        CodeBlock.Builder mb = CodeBlock.builder();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) mb.add(", ");
            if (i == classPos) mb.add("$T.eq($T.class)", MATCHERS, ClassName.get(x));
            else mb.add(typedAnyMatcher(ps[i]));
        }
        return mb.build();
    }

    /** A {@code <T> T m(..., Class<T>, ...)} factory method: matching name/arity, type-variable return,
     *  and a {@code Class} parameter at the position the SUT passed {@code X.class}. */
    private static Method factoryMethod(Class<?> collab, String name, int argc, int classPos) {
        for (Method m : collab.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc
                    && classPos >= 0 && classPos < m.getParameterCount()
                    && m.getParameterTypes()[classPos] == Class.class
                    && m.getGenericReturnType() instanceof java.lang.reflect.TypeVariable) {
                return m;
            }
        }
        return null;
    }

    /** bindings: type-variable -> concrete type, derived from the collaborator reference's parameterized
     *  type (e.g. field store:Store&lt;String&gt;). Lets a T-returning collaborator method stub the bound
     *  concrete (String) instead of erased Object -> avoids ClassCastException at the SUT call site. */
    private static CodeBlock minimalStubs(MockRef mock,
            java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> bindings) {
        CodeBlock.Builder stubs = CodeBlock.builder();
        for (Method m : mock.type.getMethods()) {
            if (!include(m) || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (!Config.STUB_THIRD_PARTY && !isProjectType(m.getDeclaringClass())) {
                continue;   // internal-only: mock third-party collaborators but don't stub their methods
            }
            CodeBlock anyArgs = anyMatchers(m.getParameterTypes());
            java.lang.reflect.Type gret = m.getGenericReturnType();
            Class<?> ret = m.getReturnType();
            Class<?> resolved = resolveReturnClass(gret, bindings);   // bind type-var / T[] via collaborator generics
            if (resolved != null) ret = resolved;
            boolean unresolvedTypeVar = isTypeVarReturn(gret) && (resolved == null);
            if (ret == void.class) {
                stubs.addStatement("$T.doNothing().when($L).$L($L)",
                        MOCKITO, mock.varName, m.getName(), anyArgs);
            } else if (ret.isPrimitive()) {
                stubs.addStatement("$T.doReturn($L).when($L).$L($L)",
                        MOCKITO, primitiveDefaultExpr(ret), mock.varName, m.getName(), anyArgs);
            } else if (unresolvedTypeVar) {   // T bound by the call arg (unknowable here): a bare Object cast
                stubs.addStatement("$T.doReturn(null).when($L).$L($L)",   // to the SUT's expected type would CCE; null casts to anything
                        MOCKITO, mock.varName, m.getName(), anyArgs);
            } else if (ret != Object.class && ret.isAssignableFrom(mock.type)) {   // fluent/self-returning: same mock
                stubs.addStatement("$T.doReturn($L).when($L).$L($L)",   // so chained builder calls resolve (RETURNS_SELF)
                        MOCKITO, mock.varName, mock.varName, m.getName(), anyArgs);
            } else {
                // doReturn(...).when(...) takes Object -> sidesteps the thenReturn compile-time
                // return-type check that fails on erased generic/covariant returns.
                CodeBlock rv = classReturnStub(gret);   // Class<...> -> X.class (Instancio can't build Class)
                if (rv == null) rv = optionalReturnStub(gret);   // real present Optional, not a mock
                if (rv == null) rv = entryReturnStub(gret);   // real Map.entry, not a mock
                if (rv == null) rv = futureReturnStub(gret);   // real completed future, not a mock
                if (rv == null) rv = stubValue(ret);
                stubs.addStatement("$T.doReturn($L).when($L).$L($L)",
                        MOCKITO, rv, mock.varName, m.getName(), anyArgs);
            }
        }
        return stubs.build();
    }

    /** A real completed future for a method returning CompletableFuture/Future/CompletionStage (a mocked
     *  one returns null from join()/get() -> NPE); uses the element type, else completes with null. */
    private static CodeBlock futureReturnStub(java.lang.reflect.Type gret) {
        Class<?> raw = toClass(gret);
        if (raw != java.util.concurrent.CompletableFuture.class && raw != java.util.concurrent.Future.class
                && raw != java.util.concurrent.CompletionStage.class) {
            return null;
        }
        ClassName cf = ClassName.get(java.util.concurrent.CompletableFuture.class);
        if (gret instanceof java.lang.reflect.ParameterizedType pt) {
            Class<?> elem = toClass(pt.getActualTypeArguments()[0]);
            if (elem != null && elem != Object.class) {
                return CodeBlock.of("$T.completedFuture($L)", cf, stubValue(elem));
            }
        }
        return CodeBlock.of("$T.completedFuture(null)", cf);
    }

    /** A real {@code Map.entry(k, v)} for a method returning {@code Map.Entry<K,V>} (a mocked entry
     *  returns null from getKey()/getValue() -> NPE if derefed); null when not a Map.Entry return. */
    private static CodeBlock entryReturnStub(java.lang.reflect.Type gret) {
        if (toClass(gret) != java.util.Map.Entry.class) return null;
        ClassName map = ClassName.get(java.util.Map.class);
        if (gret instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length == 2) {
            Class<?> k = toClass(pt.getActualTypeArguments()[0]);
            Class<?> v = toClass(pt.getActualTypeArguments()[1]);
            if (k != null && v != null && k != Object.class && v != Object.class) {
                return CodeBlock.of("$T.entry($L, $L)", map, stubValue(k), stubValue(v));
            }
        }
        return CodeBlock.of("$T.entry(new Object(), new Object())", map);   // raw: Map.entry rejects null
    }

    /** A concrete {@code X.class} literal for a method returning {@code Class<...>} (Instancio can't
     *  build java.lang.Class), using the wildcard/type upper bound; Object.class when raw/unbounded. */
    private static CodeBlock classReturnStub(java.lang.reflect.Type gret) {
        if (toClass(gret) != Class.class) return null;
        Class<?> bound = Object.class;
        if (gret instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof java.lang.reflect.WildcardType wt && wt.getUpperBounds().length > 0) {
                bound = toClass(wt.getUpperBounds()[0]);
            } else {
                bound = toClass(arg);
            }
        }
        if (bound == null) bound = Object.class;
        return CodeBlock.of("$T.class", ClassName.get(bound));
    }

    /** A real Optional stub value (present, with the element type) for a method returning Optional/
     *  OptionalInt/Long/Double, else null. A mocked Optional returns null from get()/map() -> caller
     *  NPEs; Optional.empty() throws on get(). A present Optional exercises get()/map()/orElse alike. */
    private static CodeBlock optionalReturnStub(java.lang.reflect.Type gret) {
        Class<?> raw = toClass(gret);
        if (raw == java.util.OptionalInt.class) return CodeBlock.of("$T.of(0)", raw);
        if (raw == java.util.OptionalLong.class) return CodeBlock.of("$T.of(0L)", raw);
        if (raw == java.util.OptionalDouble.class) return CodeBlock.of("$T.of(0.0)", raw);
        if (raw != java.util.Optional.class) return null;
        if (gret instanceof java.lang.reflect.ParameterizedType pt) {
            Class<?> elem = toClass(pt.getActualTypeArguments()[0]);
            if (elem != null && elem != Object.class) {
                return CodeBlock.of("$T.of($L)", java.util.Optional.class, stubValue(elem));
            }
        }
        return CodeBlock.of("$T.empty()", java.util.Optional.class);   // element unknown -> empty (still real)
    }

    /** Safe stub value for a non-void, non-primitive return: mock interfaces/abstract types
     *  (Instancio cannot instantiate them, so they would throw at runtime), Instancio.create concretes. */
    private static CodeBlock stubValue(Class<?> ret) {
        Class<?> sealedImpl = sealedConcrete(ret);
        if (sealedImpl != null) {   // sealed return -> permitted concrete impl (Mockito can't mock sealed)
            return CodeBlock.of("$T.create($T.class)", INSTANCIO, ClassName.get(sealedImpl));
        }
        if (ret.isArray()) {
            Class<?> comp = ret.getComponentType();
            if (comp.isInterface() || (!comp.isPrimitive() && !comp.isArray()
                    && java.lang.reflect.Modifier.isAbstract(comp.getModifiers()))) {
                return CodeBlock.of("new $T[0]", ClassName.get(comp));   // Instancio fills iface/abstract elems with null → NPE
            }
        }
        TypeName tn = javaPoetType(ret);
        Class<?> coll = collectionImpl(ret);
        if (coll != null) {   // empty real collection: a mocked List returns isEmpty()==false & get(i)==null → NPE
            return CodeBlock.of("new $T<>()", ClassName.get(coll));
        }
        // mock interfaces/abstracts AND generic types (Instancio can't build Foo<T>/Optional<T> raw).
        // arrays report isAbstract()==true but are not mockable — Instancio.create them.
        boolean mockable = !ret.isArray() && ret != Class.class   // Class is generic but Mockito refuses to mock it
                && (ret.isInterface() || java.lang.reflect.Modifier.isAbstract(ret.getModifiers())
                        || ret.getTypeParameters().length > 0);
        if (mockable) {
            return CodeBlock.of("$T.mock($T.class)", MOCKITO, tn);
        }
        return CodeBlock.of("$T.create($T.class)", INSTANCIO, tn);
    }

    private static CodeBlock anyMatchers(Class<?>[] params) {
        if (params.length == 0) {
            return CodeBlock.of("");
        }
        CodeBlock.Builder sb = CodeBlock.builder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.add(", ");
            }
            sb.add(typedAnyMatcher(params[i]));
        }
        return sb.build();
    }

    /** Type-aware matcher: primitives use anyX(); references use any(Type.class) so overloads disambiguate. */
    private static CodeBlock typedAnyMatcher(Class<?> p) {
        if (p == int.class)     return CodeBlock.of("$T.anyInt()", MATCHERS);
        if (p == long.class)    return CodeBlock.of("$T.anyLong()", MATCHERS);
        if (p == double.class)  return CodeBlock.of("$T.anyDouble()", MATCHERS);
        if (p == float.class)   return CodeBlock.of("$T.anyFloat()", MATCHERS);
        if (p == short.class)   return CodeBlock.of("$T.anyShort()", MATCHERS);
        if (p == byte.class)    return CodeBlock.of("$T.anyByte()", MATCHERS);
        if (p == char.class)    return CodeBlock.of("$T.anyChar()", MATCHERS);
        if (p == boolean.class) return CodeBlock.of("$T.anyBoolean()", MATCHERS);
        return CodeBlock.of("$T.any($T.class)", MATCHERS, javaPoetType(p));
    }

    private static String primitiveDefaultExpr(Class<?> prim) {
        if (prim == boolean.class) return "false";
        if (prim == char.class) return "'\\0'";
        if (prim == long.class) return "0L";
        if (prim == float.class) return "0f";
        if (prim == double.class) return "0.0";
        if (prim == byte.class) return "(byte) 0";     // doReturn(int 0) on byte-returning mock -> WrongTypeOfReturnValue
        if (prim == short.class) return "(short) 0";   // same for short
        return "0";   // int
    }

    private static List<Method> contractInstanceMethods(Class<?> sut) {
        List<Method> declared = filterInstance(sut.getDeclaredMethods());
        if (!isThin(declared)) {
            return dedupe(declared);
        }
        LinkedHashSet<Method> contract = new LinkedHashSet<>(declared);
        for (Class<?> iface : allInterfaces(sut)) {
            contract.addAll(filterInstance(iface.getMethods()));
        }
        Class<?> parent = sut.getSuperclass();
        if (parent != null && parent != Object.class) {
            contract.addAll(filterInstance(parent.getDeclaredMethods()));
        }
        return dedupe(new ArrayList<>(contract));
    }

    private static List<Method> contractStaticMethods(Class<?> sut) {
        List<Method> declared = filterStatic(sut.getDeclaredMethods());
        if (!declared.isEmpty()) {
            return dedupe(declared);
        }
        LinkedHashSet<Method> contract = new LinkedHashSet<>();
        for (Class<?> iface : allInterfaces(sut)) {
            contract.addAll(filterStatic(iface.getMethods()));
        }
        Class<?> parent = sut.getSuperclass();
        if (parent != null && parent != Object.class) {
            contract.addAll(filterStatic(parent.getDeclaredMethods()));
        }
        return dedupe(new ArrayList<>(contract));
    }

    private static boolean isThin(List<Method> declared) {
        if (declared.isEmpty()) {
            return true;
        }
        for (Method m : declared) {
            if (!looksLikeAccessor(m)) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeAccessor(Method m) {
        if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
            return false;
        }
        String n = m.getName();
        return n.startsWith("get") || n.startsWith("is");
    }

    private static List<Class<?>> allInterfaces(Class<?> type) {
        LinkedHashSet<Class<?>> out = new LinkedHashSet<>();
        Class<?> cur = type;
        while (cur != null && cur != Object.class) {
            out.addAll(Arrays.asList(cur.getInterfaces()));
            for (Class<?> iface : cur.getInterfaces()) {
                collectInterfaces(iface, out);
            }
            cur = cur.getSuperclass();
        }
        return new ArrayList<>(out);
    }

    private static void collectInterfaces(Class<?> iface, Set<Class<?>> out) {
        if (!out.add(iface)) {
            return;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            collectInterfaces(parent, out);
        }
    }

    private static List<Method> filterInstance(Method[] methods) {
        List<Method> out = new ArrayList<>();
        for (Method m : methods) {
            if (include(m) && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                out.add(m);
            }
        }
        return out;
    }

    private static List<Method> filterStatic(Method[] methods) {
        List<Method> out = new ArrayList<>();
        for (Method m : methods) {
            if (include(m) && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                out.add(m);
            }
        }
        return out;
    }

    private static List<Method> dedupe(List<Method> methods) {
        List<Method> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Method m : methods) {
            String key = m.getName() + Arrays.toString(m.getParameterTypes());
            if (seen.add(key)) {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean include(Method m) {
        int mod = m.getModifiers();
        if (!java.lang.reflect.Modifier.isPublic(mod)) {
            return false;
        }
        if (m.isSynthetic() || m.isBridge()) {
            return false;
        }
        String n = m.getName();
        if (n.equals("equals") || n.equals("hashCode") || n.equals("toString") || n.equals("getClass")
                || n.equals("notify") || n.equals("notifyAll") || n.equals("wait")) {
            return false;
        }
        Class<?> dc = m.getDeclaringClass();
        if (dc == Enum.class
                || (dc.isEnum() && (n.equals("values") || n.equals("valueOf") || n.equals("compareTo")))) {
            return false;   // synthesized enum values()/valueOf/compareTo + java.lang.Enum internals
        }
        return true;
    }

    private static String safeName(Method m) {
        String base = m.getName();
        if (m.getParameterCount() == 0) {
            return base;
        }
        return base + "_" + m.getParameterCount() + "args";
    }

    private static String uniqueName(String base, Set<String> usedNames) {
        String name = base;
        int i = 2;
        while (!usedNames.add(name)) {
            name = base + "_" + i++;
        }
        return name;
    }

    private static String simpleParamName(Parameter p) {
        String n = p.getName();
        if (n == null || n.startsWith("arg")) {
            n = p.getType().getSimpleName();
        }
        return n.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static Class<?> box(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == short.class) return Short.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }

    // ---- static / construction isolation: mockStatic / mockConstruction in try-with-resources ----

    /** Parse the SUT method body for static-call targets and new'd types (project types only). */
    private static SutStatics analyzeStaticsAndNew(Class<?> sut, Method method) {
        try {
            Path src = Config.mainSourceRoot().resolve(sut.getName().replace('.', '/') + ".java");
            if (!Files.isRegularFile(src)) {
                return null;
            }
            CompilationUnit cu = StaticJavaParser.parse(src);
            String pkg = sut.getPackageName();
            MethodDeclaration target = null;
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                if (md.getNameAsString().equals(method.getName())
                        && md.getParameters().size() == method.getParameterCount()) {
                    target = md;
                    break;
                }
            }
            if (target == null) {
                return null;
            }
            LinkedHashSet<Class<?>> statics = new LinkedHashSet<>();
            LinkedHashSet<Class<?>> constructed = new LinkedHashSet<>();
            for (MethodCallExpr call : target.findAll(MethodCallExpr.class)) {
                if (call.getScope().isPresent()) {
                    com.github.javaparser.ast.expr.Expression s = call.getScope().get();
                    String q = null;
                    if (s instanceof NameExpr ne && !ne.getNameAsString().isEmpty()
                            && Character.isUpperCase(ne.getNameAsString().charAt(0))) {
                        q = ne.getNameAsString();                       // simple: Class.method()
                    } else if (s instanceof com.github.javaparser.ast.expr.FieldAccessExpr fae) {
                        q = fae.toString();                             // fully-qualified: com.pkg.Class.method()
                    }
                    if (q != null) {
                        Class<?> c = q.contains(".") ? tryLoad(q) : resolveSimple(cu, pkg, q);
                        if (c != null && isProjectType(c) && !c.equals(sut)) {
                            statics.add(c);
                        }
                    }
                } else {
                    String mname = call.getNameAsString();              // no scope: static import?
                    for (ImportDeclaration imp : cu.getImports()) {
                        if (imp.isStatic() && !imp.isAsterisk() && imp.getNameAsString().endsWith("." + mname)) {
                            String cls = imp.getNameAsString().substring(0, imp.getNameAsString().lastIndexOf('.'));
                            Class<?> c = tryLoad(cls);
                            if (c != null && isProjectType(c) && !c.equals(sut)) {
                                statics.add(c);
                            }
                        }
                    }
                }
            }
            for (ObjectCreationExpr oce : target.findAll(ObjectCreationExpr.class)) {
                Class<?> c = resolveSimple(cu, pkg, oce.getType().getNameAsString());
                if (c != null && isProjectType(c) && !c.equals(sut)) {   // never mock-construct the SUT's own type
                    constructed.add(c);
                }
            }
            return new SutStatics(statics, constructed);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> resolveSimple(CompilationUnit cu, String sutPkg, String simple) {
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && imp.getNameAsString().endsWith("." + simple)) {
                Class<?> c = tryLoad(imp.getNameAsString());
                if (c != null) {
                    return c;
                }
            }
        }
        Class<?> c = tryLoad(sutPkg + "." + simple);
        return (c != null) ? c : tryLoad("java.lang." + simple);
    }

    private static Class<?> tryLoad(String fqn) {
        try {
            return Class.forName(fqn, false, TestSkeletonGenerator.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** try-with-resources header: one MockedStatic per static target, one MockedConstruction per new'd type. */
    private static CodeBlock mockScopeResources(SutStatics st) {
        CodeBlock.Builder r = CodeBlock.builder();
        boolean first = true;
        for (Class<?> x : st.staticTargets()) {
            if (!first) {
                r.add(";\n");
            }
            first = false;
            r.add("$T<$T> $L = $T.mockStatic($T.class)",
                    MOCKED_STATIC, ClassName.get(x), msVar(x), MOCKITO, ClassName.get(x));
        }
        for (Class<?> y : st.constructedTypes()) {
            if (!first) {
                r.add(";\n");
            }
            first = false;
            CodeBlock init = minimalStubs(new MockRef(y, "cm"));   // stub the constructed mock's own methods
            if (init.isEmpty()) {
                r.add("$T<$T> $L = $T.mockConstruction($T.class)",
                        MOCKED_CONSTRUCTION, ClassName.get(y), mcVar(y), MOCKITO, ClassName.get(y));
            } else {   // 2-arg form: else new T() returns a mock whose methods return null -> caller NPEs
                r.add("$T<$T> $L = $T.mockConstruction($T.class, (cm, context) -> {\n$>$L$<})",
                        MOCKED_CONSTRUCTION, ClassName.get(y), mcVar(y), MOCKITO, ClassName.get(y), init);
            }
        }
        return r.build();
    }

    /** Stub each static target's non-void static methods so the isolated call graph does not NPE. */
    private static void emitStaticStubs(MethodSpec.Builder b, SutStatics st, Class<?> sut) {
        for (Class<?> x : st.staticTargets()) {
            for (Method sm : x.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(sm.getModifiers()) || !include(sm)) {
                    continue;
                }
                java.lang.reflect.Type gret = sm.getGenericReturnType();
                Class<?> ret = sm.getReturnType();
                if (ret == void.class) {
                    continue;
                }
                CodeBlock matchers = anyMatchers(sm.getParameterTypes());
                if (ret.isPrimitive()) {
                    b.addStatement("$L.when(() -> $T.$L($L)).thenReturn($L)",
                            msVar(x), ClassName.get(x), sm.getName(), matchers, primitiveDefaultExpr(ret));
                } else if (isTypeVarReturn(gret)) {   // unresolved static type-var: null (a bare Object would CCE)
                    b.addStatement("$L.when(() -> $T.$L($L)).thenReturn(null)",
                            msVar(x), ClassName.get(x), sm.getName(), matchers);
                } else {
                    CodeBlock rv = classReturnStub(gret);   // Class<...> -> X.class
                    if (rv == null) rv = optionalReturnStub(gret);   // real Optional, not mock
                    if (rv == null) rv = entryReturnStub(gret);   // real Map.entry, not mock
                    if (rv == null) rv = futureReturnStub(gret);   // real completed future, not mock
                    if (rv == null) rv = stubValue(ret);
                    b.addStatement("$L.when(() -> $T.$L($L)).thenReturn($L)",
                            msVar(x), ClassName.get(x), sm.getName(), matchers, rv);
                }
            }
            b.addCode(staticFactoryStubs(sut, x));   // call-site X.class binds a static <T> T m(Class<T>) factory
        }
    }

    /** Static analog of {@link #factoryStubs}: for {@code X.m(Y.class)} call sites on a static factory,
     *  emit {@code msVar.when(() -> X.m(eq(Y.class))).thenReturn(<value Y>)}. */
    private static CodeBlock staticFactoryStubs(Class<?> sut, Class<?> collab) {
        CodeBlock.Builder out = CodeBlock.builder();
        try {
            Path src = Config.mainSourceRoot().resolve(sut.getName().replace('.', '/') + ".java");
            if (!Files.isRegularFile(src)) return out.build();
            CompilationUnit cu = StaticJavaParser.parse(src);
            String pkg = sut.getPackageName();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (call.getScope().isEmpty()) continue;
                com.github.javaparser.ast.expr.Expression scope = call.getScope().get();
                String scopeName = scope instanceof NameExpr ne ? ne.getNameAsString() : scope.toString();
                if (!collab.getSimpleName().equals(scopeName) && !collab.getName().equals(scopeName)) continue;
                int[] pos = {-1};
                Class<?> y = classArg(call, cu, pkg, pos);
                if (y == null) continue;
                Method fm = factoryMethod(collab, call.getNameAsString(), call.getArguments().size(), pos[0]);
                if (fm == null || !java.lang.reflect.Modifier.isStatic(fm.getModifiers())) continue;
                if (!seen.add(call.getNameAsString() + "|" + pos[0] + "|" + y.getName())) continue;
                out.addStatement("$L.when(() -> $T.$L($L)).thenReturn($L)",
                        msVar(collab), ClassName.get(collab), call.getNameAsString(),
                        factoryMatchers(fm, pos[0], y), stubValue(y));
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        return out.build();
    }

    private static String msVar(Class<?> x) {
        return "ms" + x.getSimpleName();
    }

    private static String mcVar(Class<?> y) {
        return "mc" + y.getSimpleName();
    }

    /**
     * TG-BOILERDET: detected facades never get mockStatic/mockConstruction — the delegate-swap
     * fixture controls them, and a static mock of the facade is exactly the collision the play
     * exists to remove.
     */
    private static SutStatics suppressFacades(SutStatics st, Set<Class<?>> facadeSuppress) {
        if (st == null || facadeSuppress.isEmpty()) {
            return st;
        }
        LinkedHashSet<Class<?>> statics = new LinkedHashSet<>(st.staticTargets());
        LinkedHashSet<Class<?>> constructed = new LinkedHashSet<>(st.constructedTypes());
        statics.removeAll(facadeSuppress);
        constructed.removeAll(facadeSuppress);
        return new SutStatics(statics, constructed);
    }

    private record SutStatics(Set<Class<?>> staticTargets, Set<Class<?>> constructedTypes) {}

    private record MockRef(Class<?> type, String varName) {}

    private record FieldMockRef(Class<?> type, String varName, Class<?> declaringClass, String fieldName,
                                boolean skipStub) {}
}

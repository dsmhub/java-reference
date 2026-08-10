package com.testgen.fixtures;

import com.config.Config;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * TG-BOILERDET detection pass: statically decide, per SUT, whether the test needs the
 * delegate-swap boilerplate (mockito-static-lab LoggerFixture/ConfigFixture play).
 *
 * A FACADE is a project class F with a static accessor returning F (singleton shape) and at
 * least one swappable delegate field (non-final static, or instance, reference-typed state the
 * lab play swaps and restores). A SUT CONSUMES a facade when the SUT, a superclass, or a hop-1
 * collaborator (field / constructor-param project type) declares a field of type F or calls
 * F's static accessor in source — this catches config-in-constructor and static-init reads.
 *
 * Pure detection: no source is modified; the skeleton generator decides what to emit.
 *
 * WHY THESE TARGETS - the three-leg test. Most statics are harmless furniture; a fixture is
 * needed only when all three legs hold:
 *   1. SHARED    - static state: one copy per JVM, every test touches the same one.
 *   2. MUTABLE   - it can change after birth: a swappable delegate, a map, settable levels.
 *   3. CONSULTED - the behaviour under test actually reads or writes it.
 * Static methods hold no state (nothing leaks); static final constants cannot change (nothing
 * to undo); static nested classes are naming only. Logger, config, cache and JNDI registries
 * pass all three legs - that family is what this scanner hunts, and the three legs are exactly
 * the checks encoded in facadeShape/swappableDelegates/consumer detection below.
 */
public final class SMCFixtureScanner {

    private SMCFixtureScanner() {}

    /** One swappable delegate on a facade. */
    public record DelegateField(String name, Class<?> type, boolean staticField, boolean mapShaped) {}

    /** One detected facade with its accessor and swappable delegates. */
    public record Facade(Class<?> facadeClass, Method accessor, List<DelegateField> delegates) {

        /** True when any delegate is instance-scoped, so the fixture must fetch the singleton. */
        boolean needsInstance() {
            return delegates.stream().anyMatch(d -> !d.staticField());
        }
    }

    /**
     * Detects every facade the SUT-set consumes. Deterministic order: SUT first, then parents,
     * then collaborators in declaration order.
     */
    public static List<Facade> detect(Class<?> sut) {
        LinkedHashSet<Class<?>> consumerSet = consumerSet(sut);
        LinkedHashSet<Class<?>> facadeClasses = new LinkedHashSet<>();

        for (Class<?> consumer : consumerSet) {
            collectFacadesFromFields(consumer, facadeClasses);
            collectFacadesFromSource(consumer, facadeClasses);
            collectFacadesFromSignatures(consumer, facadeClasses);
        }
        facadeClasses.remove(sut);   // a facade never needs a fixture inside its own test

        List<Facade> out = new ArrayList<>();
        for (Class<?> f : facadeClasses) {
            Facade facade = asFacade(f);
            if (facade != null) {
                out.add(facade);
            }
        }
        return out;
    }

    /** Facade classes for one SUT as a set, for suppressing mockStatic on them. */
    public static Set<Class<?>> facadeClasses(List<Facade> facades) {
        LinkedHashSet<Class<?>> out = new LinkedHashSet<>();
        for (Facade f : facades) {
            out.add(f.facadeClass());
        }
        return out;
    }

    /** SUT + superclass chain (stop at java.*) + hop-1 collaborator project types. */
    private static LinkedHashSet<Class<?>> consumerSet(Class<?> sut) {
        LinkedHashSet<Class<?>> out = new LinkedHashSet<>();
        Class<?> cur = sut;
        while (cur != null && cur != Object.class && !cur.getName().startsWith("java.")) {
            out.add(cur);
            cur = cur.getSuperclass();
        }
        for (Class<?> base : new ArrayList<>(out)) {
            for (Field f : base.getDeclaredFields()) {
                if (!f.isSynthetic() && isProjectClass(baseType(f.getType()))) {
                    out.add(baseType(f.getType()));
                }
            }
            for (var ctor : base.getDeclaredConstructors()) {
                for (Class<?> p : ctor.getParameterTypes()) {
                    if (isProjectClass(baseType(p))) {
                        out.add(baseType(p));
                    }
                }
            }
        }
        return out;
    }

    /** Field of a facade-shaped project type anywhere in the consumer → facade hit. */
    private static void collectFacadesFromFields(Class<?> consumer, Set<Class<?>> out) {
        for (Field f : consumer.getDeclaredFields()) {
            if (f.isSynthetic()) {
                continue;
            }
            Class<?> ft = baseType(f.getType());
            if (ft != consumer && isProjectClass(ft) && asFacade(ft) != null) {
                out.add(ft);
            }
        }
    }

    /**
     * TWEAK-POINT (detection): static call {@code F.accessor(...)} in the consumer's source —
     * simple name (resolved through imports/package) OR fully-qualified — and facade-typed
     * LOCAL VARIABLE declarations. Method bodies are the reliable gun when no field exists.
     */
    private static void collectFacadesFromSource(Class<?> consumer, Set<Class<?>> out) {
        Optional<CompilationUnit> cu = parseSource(consumer);
        if (cu.isEmpty()) {
            return;
        }
        String pkg = consumer.getPackageName();
        for (MethodCallExpr call : cu.get().findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) {
                continue;
            }
            Class<?> resolved = null;
            if (call.getScope().get() instanceof NameExpr ne) {
                String scope = ne.getNameAsString();
                if (!scope.isEmpty() && Character.isUpperCase(scope.charAt(0))) {
                    resolved = resolveSimple(cu.get(), pkg, scope);
                }
            } else if (call.getScope().get() instanceof com.github.javaparser.ast.expr.FieldAccessExpr fae) {
                resolved = tryLoad(fae.toString());   // fully-qualified accessor call, no import needed
            }
            if (resolved == null || resolved == consumer || !isProjectClass(resolved)) {
                continue;
            }
            Facade facade = asFacade(resolved);
            if (facade != null && facade.accessor().getName().equals(call.getNameAsString())) {
                out.add(resolved);
            }
        }
        for (com.github.javaparser.ast.body.VariableDeclarator var
                : cu.get().findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            String typeName = var.getType().asString().replaceAll("<.*>", "").trim();
            Class<?> resolved = typeName.contains(".") ? tryLoad(typeName)
                    : resolveSimple(cu.get(), pkg, typeName);
            if (resolved != null && resolved != consumer && isProjectClass(resolved)
                    && asFacade(resolved) != null) {
                out.add(resolved);
            }
        }
    }

    /**
     * TWEAK-POINT (detection): facade types arriving through SIGNATURES — constructor params,
     * method params, and return types (reflection; no source needed).
     */
    private static void collectFacadesFromSignatures(Class<?> consumer, Set<Class<?>> out) {
        java.util.List<Class<?>> candidates = new ArrayList<>();
        for (var ctor : consumer.getDeclaredConstructors()) {
            candidates.addAll(java.util.Arrays.asList(ctor.getParameterTypes()));
        }
        for (Method m : consumer.getDeclaredMethods()) {
            candidates.addAll(java.util.Arrays.asList(m.getParameterTypes()));
            candidates.add(m.getReturnType());
        }
        for (Class<?> c : candidates) {
            Class<?> base = baseType(c);
            if (base != consumer && isProjectClass(base) && asFacade(base) != null) {
                out.add(base);
            }
        }
    }


    /**
     * Facade shape test. Returns the facade model, or null when the class does not qualify or
     * its accessor takes parameters the fixture cannot synthesize (Class/String only).
     */
    private static Facade asFacade(Class<?> candidate) {
        if (candidate == null || !isProjectClass(candidate)) {
            return null;
        }
        Method accessor = singletonAccessor(candidate);
        if (accessor == null) {
            return null;
        }
        if (!hasSelfTypedHolder(candidate)) {
            return null;   // D4: factory shape (fresh object per call) - swapping it is a no-op
        }
        List<DelegateField> delegates = swappableDelegates(candidate);
        if (delegates.isEmpty()) {
            if (hasFrozenDelegates(candidate)) {
                System.out.println("NOTE facade-like but frozen (static-final delegates only), "
                        + "unsupported: " + candidate.getName());
            }
            return null;
        }
        return new Facade(candidate, accessor, delegates);
    }

    /** Public static method returning the class itself, with only synthesizable params. */
    private static Method singletonAccessor(Class<?> candidate) {
        for (Method m : candidate.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())
                    || m.isSynthetic() || m.getReturnType() != candidate) {
                continue;
            }
            boolean synthesizable = true;
            for (Class<?> p : m.getParameterTypes()) {
                if (p != Class.class && p != String.class) {
                    synthesizable = false;
                }
            }
            if (synthesizable) {
                return m;
            }
        }
        return null;
    }

    /**
     * Swappable delegates: reference-typed mutable state the play controls. Static-final is
     * frozen (unswappable); value-shaped types (primitive/boxed/String/enum) are config data,
     * not delegates; the self-typed INSTANCE field is the singleton itself.
     */
    private static List<DelegateField> swappableDelegates(Class<?> candidate) {
        List<DelegateField> out = new ArrayList<>();
        for (Field f : candidate.getDeclaredFields()) {
            if (f.isSynthetic()) {
                continue;
            }
            boolean statik = Modifier.isStatic(f.getModifiers());
            if (statik && Modifier.isFinal(f.getModifiers())) {
                continue;
            }
            Class<?> ft = f.getType();
            if (ft == candidate || ft.isPrimitive() || ft.isEnum() || isValueShaped(ft)) {
                continue;
            }
            if (ft == Class.class) {
                continue;   // D3: Mockito cannot mock java.lang.Class - not a swappable delegate
            }
            out.add(new DelegateField(f.getName(), ft, statik, java.util.Map.class.isAssignableFrom(ft)));
        }
        return out;
    }


    /** D4 guard: a real singleton holds itself in a static field; factories do not. */
    private static boolean hasSelfTypedHolder(Class<?> candidate) {
        for (Field f : candidate.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == candidate) {
                return true;
            }
        }
        return false;
    }

    /** D5 visibility: reference-typed static-final state exists but is frozen by contract. */
    private static boolean hasFrozenDelegates(Class<?> candidate) {
        for (Field f : candidate.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())
                    && !f.getType().isPrimitive() && f.getType() != candidate
                    && !f.isSynthetic()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValueShaped(Class<?> type) {
        return type == String.class || type == Integer.class || type == Long.class
                || type == Double.class || type == Float.class || type == Short.class
                || type == Byte.class || type == Boolean.class || type == Character.class;
    }

    /** Project class iff its .java source exists under the main source root. */
    private static boolean isProjectClass(Class<?> type) {
        if (type == null || type.isPrimitive()
                || type.getPackageName().startsWith("java.")
                || type.getPackageName().startsWith("javax.")) {
            return false;
        }
        return Files.isRegularFile(sourcePath(type));
    }

    private static Path sourcePath(Class<?> type) {
        Class<?> outer = type;
        while (outer.getEnclosingClass() != null) {
            outer = outer.getEnclosingClass();
        }
        return Config.mainSourceRoot()
                .resolve(outer.getPackageName().replace('.', '/'))
                .resolve(outer.getSimpleName() + ".java");
    }

    private static Optional<CompilationUnit> parseSource(Class<?> type) {
        Path src = sourcePath(type);
        if (!Files.isRegularFile(src)) {
            return Optional.empty();
        }
        try {
            return Optional.of(StaticJavaParser.parse(src));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Class<?> resolveSimple(CompilationUnit cu, String pkg, String simple) {
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic() && imp.getNameAsString().endsWith("." + simple)) {
                Class<?> c = tryLoad(imp.getNameAsString());
                if (c != null) {
                    return c;
                }
            }
        }
        return tryLoad(pkg + "." + simple);
    }

    private static Class<?> tryLoad(String fqcn) {
        try {
            return Class.forName(fqcn, false, SMCFixtureScanner.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> baseType(Class<?> type) {
        Class<?> cur = type;
        while (cur != null && cur.isArray()) {
            cur = cur.getComponentType();
        }
        return cur;
    }
}

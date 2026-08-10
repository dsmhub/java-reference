package com.testgen.graphs;

import com.config.Config;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ROADMAP tool 2: static call-chain from SUT methods (JavaParser, source-only).
 * Output: {@code MODULE/testgen/reports/callchain/{Fqcn}__{method}.md}
 */
public final class CallChainScanner {

    private CallChainScanner() {}

    public static void run() throws Exception {
        Path outDir = Config.reportsDir().resolve("callchain");
        Files.createDirectories(outDir);
        Path root = Config.mainSourceRoot();
        int maxDepth = Math.max(1, Config.CALL_CHAIN_MAX_DEPTH);
        int wrote = 0;
        for (String target : Config.readMethodTargets()) {
            try {
                String fqcn = target;
                String method = null;
                int hash = target.indexOf('#');
                if (hash > 0) {
                    fqcn = target.substring(0, hash).trim();
                    method = target.substring(hash + 1).trim();
                }
                if (method == null || method.isBlank()) {
                    java.util.Map<String,Integer> seen = new java.util.HashMap<>();
                    for (String m : listPublicMethods(root, fqcn)) {
                        int n = seen.merge(m, 1, Integer::sum);
                        String fileKey = (n == 1) ? m : m + "_" + n;   // BUG-1: overloads must not overwrite
                        wrote += writeOne(outDir, root, fqcn, m, fileKey, maxDepth) ? 1 : 0;
                    }
                } else {
                    wrote += writeOne(outDir, root, fqcn, method, method, maxDepth) ? 1 : 0;
                }
            } catch (Throwable t) {   // isolate per-target: one bad target must not abort the batch
                System.out.println("SKIP callchain " + target + " -> " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }
        System.out.println("CallChain done. " + wrote + " report(s) in " + outDir.toAbsolutePath());
    }

    private static boolean writeOne(Path outDir, Path root, String fqcn, String method, String fileKey, int maxDepth)
            throws Exception {
        String md = analyze(root, fqcn, method, maxDepth);
        Path out = outDir.resolve(fqcn + "__" + fileKey + ".md");   // BUG-1: fileKey disambiguates overloads
        Files.writeString(out, md);
        System.out.println("Wrote " + out);
        return true;
    }

    public static String analyze(Path mainSourceRoot, String fqcn, String method, int maxDepth) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# callchain\n");
        sb.append("sut: ").append(fqcn).append('#').append(method).append('\n');
        sb.append("maxDepth: ").append(maxDepth).append('\n');
        sb.append('\n');
        List<String> lines = new ArrayList<>();
        walk(mainSourceRoot, fqcn, method, 0, maxDepth, new LinkedHashSet<>(), lines, "");
        if (lines.isEmpty()) {
            sb.append("- (no outbound calls found or source missing)\n");
        } else {
            for (String line : lines) {
                sb.append(line).append('\n');
            }
        }
        sb.append('\n');
        sb.append("## notes\n");
        sb.append("- Static only; no reflection/DI/poly resolution.\n");
        return sb.toString();
    }

    private static void walk(
            Path root,
            String fqcn,
            String method,
            int depth,
            int maxDepth,
            Set<String> stack,
            List<String> out,
            String indent) throws Exception {
        if (depth >= maxDepth) {
            return;
        }
        String key = fqcn + "#" + method;
        if (!stack.add(key)) {
            out.add(indent + "- (cycle " + key + ")");
            return;
        }
        Path src = sourcePath(root, fqcn);
        if (!Files.isRegularFile(src)) {
            out.add(indent + "- (no source " + fqcn + ")");
            stack.remove(key);
            return;
        }
        CompilationUnit cu = StaticJavaParser.parse(src);
        Optional<MethodDeclaration> md = findMethod(cu, simpleName(fqcn), method);
        if (md.isEmpty()) {
            out.add(indent + "- (method not found " + key + ")");
            stack.remove(key);
            return;
        }
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Map<String, String> fieldTypes = fieldTypes(cu, simpleName(fqcn));
        Map<String, String> localTypes = localVarTypes(md.get(), cu, pkg);
        for (ObjectCreationExpr oce : md.get().findAll(ObjectCreationExpr.class)) {
            String t = resolveTypeName(cu, pkg, oce.getType().getNameAsString());
            String loc = src.getFileName() + ":" + oce.getBegin().map(p -> p.line).orElse(-1);
            out.add(indent + "- " + simpleName(fqcn) + "." + method + " -> new " + simpleName(t) + " (" + loc + ")");
        }
        int n = 0;
        for (MethodCallExpr call : md.get().findAll(MethodCallExpr.class)) {
            if (n++ >= Config.CALL_CHAIN_MAX_EDGES) {
                out.add(indent + "- … capped");
                break;
            }
            String calleeName = call.getNameAsString();
            String scopeType = resolveScopeType(call, root, fieldTypes, localTypes, cu, pkg, fqcn);
            String loc = src.getFileName() + ":" + call.getBegin().map(p -> p.line).orElse(-1);
            String edge = simpleName(fqcn) + "." + method + " -> "
                    + (scopeType != null ? simpleName(scopeType) + "." : "")
                    + calleeName + " (" + loc + ")";
            out.add(indent + "- " + edge);
            if (scopeType != null && !scopeType.startsWith("java.") && depth + 1 < maxDepth) {
                walk(root, scopeType, calleeName, depth + 1, maxDepth, stack, out, indent + "  ");
            }
        }
        stack.remove(key);
    }

    private static String resolveScopeType(MethodCallExpr call, Path root, Map<String, String> fieldTypes,
            Map<String, String> localTypes, CompilationUnit cu, String pkg, String ownerFqcn) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return ownerFqcn; // same class / this
        }
        Expression s = scope.get();
        if (s instanceof NameExpr ne) {
            String n = ne.getNameAsString();
            if ("this".equals(n)) {
                return ownerFqcn;
            }
            if (localTypes.containsKey(n)) {
                return localTypes.get(n);   // local variable / parameter receiver
            }
            if (fieldTypes.containsKey(n)) {
                return fieldTypes.get(n);
            }
            if (!n.isEmpty() && Character.isUpperCase(n.charAt(0))) {
                return resolveTypeName(cu, pkg, n);   // static-class receiver
            }
            return null;
        }
        if (s instanceof FieldAccessExpr fa && "this".equals(fa.getScope().toString())) {
            return fieldTypes.get(fa.getNameAsString());
        }
        if (s instanceof MethodCallExpr inner) {
            // chained call a().b(): resolve inner receiver, then look up inner's DECLARED return type
            String innerOwner = resolveScopeType(inner, root, fieldTypes, localTypes, cu, pkg, ownerFqcn);
            if (innerOwner != null) {
                return returnTypeOf(root, innerOwner, inner.getNameAsString());
            }
        }
        return null;
    }

    /** Declared return type of ownerFqcn#methodName, resolved to a FQN (deterministic, from source). */
    private static String returnTypeOf(Path root, String ownerFqcn, String methodName) {
        try {
            Path src = sourcePath(root, ownerFqcn);
            if (!Files.isRegularFile(src)) {
                return null;
            }
            CompilationUnit ocu = StaticJavaParser.parse(src);
            String opkg = ocu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (MethodDeclaration m : ocu.findAll(MethodDeclaration.class)) {
                if (m.getNameAsString().equals(methodName)) {
                    String rt = m.getType().asString();
                    return "void".equals(rt) ? null : resolveTypeName(ocu, opkg, rt);
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Local variable + parameter names -> resolved (erased) type, for receiver resolution. */
    private static Map<String, String> localVarTypes(MethodDeclaration md, CompilationUnit cu, String pkg) {
        Map<String, String> map = new LinkedHashMap<>();
        for (VariableDeclarationExpr v : md.findAll(VariableDeclarationExpr.class)) {
            v.getVariables().forEach(var -> {
                String t = var.getType().asString().replaceAll("<.*>", "").trim();
                if (!t.isEmpty() && Character.isJavaIdentifierStart(t.charAt(0))) {
                    map.put(var.getNameAsString(), resolveTypeName(cu, pkg, t));
                }
            });
        }
        md.getParameters().forEach(p -> {
            String t = p.getType().asString().replaceAll("<.*>", "").trim();
            if (!t.isEmpty() && Character.isJavaIdentifierStart(t.charAt(0))) {
                map.put(p.getNameAsString(), resolveTypeName(cu, pkg, t));
            }
        });
        return map;
    }

    /** Resolve a simple type name to a FQN via imports, then java.lang, else same package. */
    private static String resolveTypeName(CompilationUnit cu, String pkg, String simple) {
        String bare = simple.replaceAll("<.*>", "").replace("[]", "").trim();
        if (bare.contains(".")) {
            return bare;
        }
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && imp.getNameAsString().endsWith("." + bare)) {
                return imp.getNameAsString();
            }
        }
        if (isJavaLang(bare)) {
            return "java.lang." + bare;
        }
        return (bare.isEmpty() || pkg.isEmpty()) ? bare : pkg + "." + bare;
    }

    private static boolean isJavaLang(String simple) {
        try {
            Class.forName("java.lang." + simple);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Map<String, String> fieldTypes(CompilationUnit cu, String simple) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!c.getNameAsString().equals(simple)) {
                continue;
            }
            c.getFields().forEach(f -> f.getVariables().forEach(v -> {
                String type = f.getElementType().asString().replaceAll("<.*>", "").trim();  // erase generics
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                if (type.isEmpty() || !Character.isJavaIdentifierStart(type.charAt(0))) {
                    return;
                }
                // Route through the import-aware resolver like every other path; the old inline
                // pkg-concat mis-packaged imported field types (TG-HOP2NAMES root cause).
                map.put(v.getNameAsString(), resolveTypeName(cu, pkg, type));
            }));
        }
        return map;
    }

    private static Optional<MethodDeclaration> findMethod(CompilationUnit cu, String simple, String method) {
        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!c.getNameAsString().equals(simple)) {
                continue;
            }
            for (MethodDeclaration m : c.getMethods()) {
                if (m.getNameAsString().equals(method)) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    static List<String> listPublicMethods(Path root, String fqcn) throws Exception {
        Path src = sourcePath(root, fqcn);
        List<String> names = new ArrayList<>();
        if (!Files.isRegularFile(src)) {
            return names;
        }
        CompilationUnit cu = StaticJavaParser.parse(src);
        String simple = simpleName(fqcn);
        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!c.getNameAsString().equals(simple)) {
                continue;
            }
            for (MethodDeclaration m : c.getMethods()) {
                if (m.isPublic() && !m.getNameAsString().equals("main")) {
                    names.add(m.getNameAsString());
                }
            }
        }
        return names;
    }

    private static Path sourcePath(Path root, String fqcn) {
        String clean = fqcn.replaceAll("<.*>", "").trim();   // never allow a generic/illegal name in a path
        Path direct = root.resolve(clean.replace('.', '/') + ".java");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        // Nested types live in their OUTER class's file (TG-HOP2NAMES: a.b.Outer.Inner or
        // Outer$Inner has no Inner.java). Walk trailing segments up until a file exists; the
        // declaration is then found by simple name inside that compilation unit.
        String walk = clean.replace('$', '.');
        int cut;
        while ((cut = walk.lastIndexOf('.')) > 0) {
            walk = walk.substring(0, cut);
            Path outer = root.resolve(walk.replace('.', '/') + ".java");
            if (Files.isRegularFile(outer)) {
                return outer;
            }
        }
        // Resolvers emit package+Simple for same-file nested types, so the outer name is not in
        // the FQCN at all. Last resort: scan the package directory for the file DECLARING the
        // simple name (bounded to one directory; deterministic first match in sorted order).
        int lastDot = clean.lastIndexOf('.');
        if (lastDot > 0) {
            Path pkgDir = root.resolve(clean.substring(0, lastDot).replace('.', '/'));
            String simple = clean.substring(lastDot + 1);
            if (Files.isDirectory(pkgDir)) {
                try (var files = Files.list(pkgDir)) {
                    for (Path candidate : files.filter(f -> f.toString().endsWith(".java"))
                            .sorted().toList()) {
                        try {
                            CompilationUnit cu = StaticJavaParser.parse(candidate);
                            if (cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                                    .anyMatch(c -> c.getNameAsString().equals(simple))) {
                                return candidate;
                            }
                        } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) { }
            }
        }
        return direct;   // original (missing) path: callers report "(no source ...)" honestly
    }

    private static String simpleName(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }
}

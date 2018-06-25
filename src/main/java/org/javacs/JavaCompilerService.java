package org.javacs;

import com.google.common.base.StandardSystemProperty;
import com.google.common.reflect.ClassPath;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.api.JavacTool;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;

public class JavaCompilerService {
    private static final Logger LOG = Logger.getLogger("main");
    // Not modifiable! If you want to edit these, you need to create a new instance
    private final Set<Path> sourcePath, classPath, docPath;
    private final ClassPath classPathIndex;
    private final JavaCompiler compiler = JavacTool.create(); // TODO switch to java 9 mechanism
    private final Javadocs docs;
    private final ClassPathIndex classes;
    // Diagnostics from the last compilation task
    private final List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
    // Use the same file manager for multiple tasks, so we don't repeatedly re-compile the same files
    private final StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(diags::add, null, Charset.defaultCharset());
    // Cache a single compiled file
    // Since the user can only edit one file at a time, this should be sufficient
    private Cache cache;

    public JavaCompilerService(Set<Path> sourcePath, Set<Path> classPath, Set<Path> docPath) {
        LOG.info("Creating a new compiler...");
        LOG.info("Source path:");
        for (Path p : sourcePath) {
            LOG.info("  " + p);
        }
        LOG.info("Class path:");
        for (Path p : classPath) {
            LOG.info("  " + p);
        }
        LOG.info("Doc path:");
        for (Path p : docPath) {
            LOG.info("  " + p);
        }
        // sourcePath and classPath can't actually be modified, because JavaCompiler remembers them from task to task
        this.sourcePath = Collections.unmodifiableSet(sourcePath);
        this.classPath = Collections.unmodifiableSet(classPath);
        this.docPath = docPath;
        this.classPathIndex = createClassPath(classPath);
        this.docs = new Javadocs(sourcePath, docPath);
        this.classes = new ClassPathIndex(classPath);
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Find all the java 8 platform .jar files */
    private static URL[] java8Platform(String javaHome) {
        Path rt = Paths.get(javaHome).resolve("lib").resolve("rt.jar");

        if (Files.exists(rt)) {
            URL[] result = {toUrl(rt)};
            return result;
        } else throw new RuntimeException(rt + " does not exist");
    }

    /** Find all the java 9 platform .jmod files */
    private static URL[] java9Platform(String javaHome) {
        Path jmods = Paths.get(javaHome).resolve("jmods");

        try {
            return Files.list(jmods)
                    .filter(path -> path.getFileName().toString().endsWith(".jmod"))
                    .map(path -> toUrl(path))
                    .toArray(URL[]::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isJava9() {
        return StandardSystemProperty.JAVA_VERSION.value().equals("9");
    }

    private static URL[] platform() {
        if (isJava9()) return java9Platform(StandardSystemProperty.JAVA_HOME.value());
        else return java8Platform(StandardSystemProperty.JAVA_HOME.value());
    }

    static URLClassLoader parentClassLoader() {
        URL[] bootstrap = platform();

        return new URLClassLoader(bootstrap, null);
    }

    private static ClassPath createClassPath(Set<Path> classPath) {
        Function<Path, Stream<URL>> url =
                p -> {
                    try {
                        return Stream.of(p.toUri().toURL());
                    } catch (MalformedURLException e) {
                        LOG.warning(e.getMessage());

                        return Stream.empty();
                    }
                };
        URL[] urls = classPath.stream().flatMap(url).toArray(URL[]::new);
        URLClassLoader cl = new URLClassLoader(urls, parentClassLoader());
        try {
            return ClassPath.from(cl);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String lastName(String p) {
        int i = p.lastIndexOf('.');
        if (i == -1) return p;
        else return p.substring(i + 1);
    }

    private Set<String> subPackages(String parentPackage) {
        Set<String> result = new HashSet<>();
        for (ClassPath.ClassInfo c : classPathIndex.getTopLevelClasses()) {
            String candidate = c.getPackageName();
            if (candidate.startsWith(parentPackage) && candidate.length() > parentPackage.length()) {
                int start = parentPackage.length() + 1;
                int end = candidate.indexOf('.', start);
                if (end == -1) end = candidate.length();
                String prefix = candidate.substring(0, end);
                result.add(prefix);
            }
        }
        return result;
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream().map(p -> p.toString()).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> options(Set<Path> sourcePath, Set<Path> classPath) {
        return Arrays.asList(
                "-classpath",
                joinPath(classPath),
                "-sourcepath",
                joinPath(sourcePath),
                // "-verbose",
                "-proc:none",
                "-g",
                // You would think we could do -Xlint:all,
                // but some lints trigger fatal errors in the presence of parse errors
                "-Xlint:cast",
                "-Xlint:deprecation",
                "-Xlint:empty",
                "-Xlint:fallthrough",
                "-Xlint:finally",
                "-Xlint:path",
                "-Xlint:unchecked",
                "-Xlint:varargs",
                "-Xlint:static");
    }

    /** Create a task that compiles a single file */
    private JavacTask singleFileTask(URI file, String contents) {
        diags.clear();
        return (JavacTask)
                compiler.getTask(
                        null,
                        fileManager,
                        diags::add,
                        options(sourcePath, classPath),
                        Collections.emptyList(),
                        Collections.singletonList(new StringFileObject(contents, file)));
    }

    private JavacTask batchTask(Collection<Path> paths) {
        diags.clear();
        List<File> files = paths.stream().map(Path::toFile).collect(Collectors.toList());
        return (JavacTask)
                compiler.getTask(
                        null,
                        fileManager,
                        diags::add,
                        options(sourcePath, classPath),
                        Collections.emptyList(),
                        fileManager.getJavaFileObjectsFromFiles(files));
    }

    List<Diagnostic<? extends JavaFileObject>> lint(Collection<Path> files) {
        JavacTask task = batchTask(files);
        try {
            task.parse();
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Collections.unmodifiableList(new ArrayList<>(diags));
    }

    /** Stores the compiled version of a single file */
    class Cache {
        final String contents;
        final URI file;
        final CompilationUnitTree root;
        final JavacTask task;
        final int line, character;

        Cache(URI file, String contents, int line, int character) {
            // If `line` is -1, recompile the entire file
            if (line == -1) {
                this.contents = contents;
            }
            // Otherwise, focus on the block surrounding line:character,
            // erasing all other block bodies and everything after the cursor in its own block
            else {
                Pruner p = new Pruner(file, contents);
                p.prune(line, character);
                this.contents = p.contents();
            }
            this.file = file;
            this.task = singleFileTask(file, contents);
            try {
                this.root = task.parse().iterator().next();
                // The results of task.analyze() are unreliable when errors are present
                // You can get at `Element` values using `Trees`
                task.analyze();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.line = line;
            this.character = character;
        }
    }

    /** Recompile if the active file has been edited, or if the active file has changed */
    private void recompile(URI file, String contents, int line, int character) {
        if (cache == null
                || !cache.file.equals(file)
                || !cache.contents.equals(contents)
                || cache.line != line
                || cache.character != character) {
            cache = new Cache(file, contents, line, character);
        }
    }

    /** Find the smallest tree that includes the cursor */
    private TreePath path(URI file, int line, int character) {
        Trees trees = Trees.instance(cache.task);
        SourcePositions pos = trees.getSourcePositions();
        long cursor = cache.root.getLineMap().getPosition(line, character);

        // Search for the smallest element that encompasses line:column
        class FindSmallest extends TreeScanner<Void, Void> {
            Tree found = null;

            boolean containsCursor(Tree tree) {
                long start = pos.getStartPosition(cache.root, tree), end = pos.getEndPosition(cache.root, tree);
                // If element has no position, give up
                if (start == -1 || end == -1) return false;
                // Check if `tree` contains line:column
                return start <= cursor && cursor <= end;
            }

            @Override
            public Void scan(Tree tree, Void nothing) {
                // This is pre-order traversal, so the deepest element will be the last one remaining in `found`
                if (containsCursor(tree)) found = tree;
                super.scan(tree, nothing);
                return null;
            }

            Optional<Tree> find(Tree root) {
                scan(root, null);
                return Optional.ofNullable(found);
            }
        }
        Tree found =
                new FindSmallest()
                        .find(cache.root)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                String.format("No TreePath to %s %d:%d", file, line, character)));

        return trees.getPath(cache.root, found);
    }

    /** Find all identifiers in scope at line:character */
    public List<Element> scopeMembers(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        Types types = cache.task.getTypes();
        TreePath path = path(file, line, character);
        Scope start = trees.getScope(path);

        class Walk {
            List<Element> result = new ArrayList<>();

            boolean isStatic(Scope s) {
                ExecutableElement method = s.getEnclosingMethod();
                if (method != null) {
                    return method.getModifiers().contains(Modifier.STATIC);
                } else return false;
            }

            boolean isStatic(Element e) {
                return e.getModifiers().contains(Modifier.STATIC);
            }

            boolean isThisOrSuper(VariableElement ve) {
                String name = ve.getSimpleName().toString();
                return name.equals("this") || name.equals("super");
            }

            // Place each member of `this` or `super` directly into `results`
            void unwrapThisSuper(VariableElement ve) {
                TypeMirror thisType = ve.asType();
                // `this` and `super` should always be instances of DeclaredType, which we'll use to check accessibility
                if (!(thisType instanceof DeclaredType)) {
                    LOG.warning(String.format("%s is not a DeclaredType", thisType));
                    return;
                }
                DeclaredType thisDeclaredType = (DeclaredType) thisType;
                Element thisElement = types.asElement(thisDeclaredType);
                for (Element thisMember : thisElement.getEnclosedElements()) {
                    if (isStatic(start) && !isStatic(thisMember)) continue;
                    if (thisMember.getSimpleName().contentEquals("<init>")) continue;

                    // Check if member is accessible from original scope
                    if (trees.isAccessible(start, thisMember, thisDeclaredType)) {
                        result.add(thisMember);
                    }
                }
            }

            // Place each member of `s` into results, and unwrap `this` and `super`
            void walkLocals(Scope s) {
                for (Element e : s.getLocalElements()) {
                    if (e instanceof TypeElement) {
                        TypeElement te = (TypeElement) e;
                        if (trees.isAccessible(start, te)) result.add(te);
                    } else if (e instanceof VariableElement) {
                        VariableElement ve = (VariableElement) e;
                        if (isThisOrSuper(ve)) {
                            unwrapThisSuper(ve);
                            if (!isStatic(s)) result.add(ve);
                        } else {
                            result.add(ve);
                        }
                    } else {
                        result.add(e);
                    }
                }
            }

            // Walk each enclosing scope, placing its members into `results`
            List<Element> walkScopes() {
                for (Scope s = start; s != null; s = s.getEnclosingScope()) {
                    walkLocals(s);
                }

                return result;
            }
        }
        return new Walk().walkScopes();
    }

    private List<TypeMirror> supersWithSelf(TypeMirror t) {
        Elements elements = cache.task.getElements();
        Types types = cache.task.getTypes();
        List<TypeMirror> result = new ArrayList<>();
        result.add(t);
        // Add members of superclasses and interfaces
        result.addAll(types.directSupertypes(t));
        // Object type is not included by default
        // We need to add it to get members like .equals(other) and .hashCode()
        // TODO this may add things twice for interfaces with no super-interfaces
        result.add(elements.getTypeElement("java.lang.Object").asType());
        return result;
    }

    private boolean hasMembers(TypeMirror type) {
        switch (type.getKind()) {
            case ARRAY:
            case DECLARED:
            case ERROR:
            case TYPEVAR:
            case WILDCARD:
            case UNION:
            case INTERSECTION:
                return true;
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case CHAR:
            case FLOAT:
            case DOUBLE:
            case VOID:
            case NONE:
            case NULL:
            case PACKAGE:
            case EXECUTABLE:
            case OTHER:
            default:
                return false;
        }
    }

    /** Find all members of expression ending at line:character */
    public List<Completion> members(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        Types types = cache.task.getTypes();
        TreePath path = path(file, line, character);
        Scope scope = trees.getScope(path);
        Element element = trees.getElement(path);

        if (element instanceof PackageElement) {
            List<Completion> result = new ArrayList<>();
            PackageElement p = (PackageElement) element;

            // Add class-names resolved as Element by javac
            for (Element member : p.getEnclosedElements()) {
                // If the package member is a TypeElement, like a class or interface, check if it's accessible
                if (member instanceof TypeElement) {
                    if (trees.isAccessible(scope, (TypeElement) member)) {
                        result.add(Completion.ofElement(member));
                    }
                }
                // Otherwise, just assume it's accessible and add it to the list
                else result.add(Completion.ofElement(member));
            }
            // Add sub-package names resolved as String by guava ClassPath
            String parent = p.getQualifiedName().toString();
            Set<String> subs = subPackages(parent);
            for (String sub : subs) {
                result.add(Completion.ofPackagePart(sub, lastName(sub)));
            }

            return result;
        } else if (element instanceof TypeElement) {
            List<Completion> result = new ArrayList<>();
            TypeElement t = (TypeElement) element;

            // Add static members
            for (Element member : t.getEnclosedElements()) {
                if (member.getModifiers().contains(Modifier.STATIC)
                        && trees.isAccessible(scope, member, (DeclaredType) t.asType())) {
                    result.add(Completion.ofElement(member));
                }
            }

            // Add .class
            result.add(Completion.ofClassSymbol(t));

            return result;
        } else {
            TypeMirror type = trees.getTypeMirror(path);
            if (hasMembers(type)) {
                List<Completion> result = new ArrayList<>();
                List<TypeMirror> ts = supersWithSelf(type);
                Set<String> alreadyAdded = new HashSet<>();
                for (TypeMirror t : ts) {
                    Element e = types.asElement(t);
                    for (Element member : e.getEnclosedElements()) {
                        // Don't add statics
                        if (member.getModifiers().contains(Modifier.STATIC)) continue;
                        // Don't add constructors
                        if (member.getSimpleName().contentEquals("<init>")) continue;
                        // Skip overridden members from superclass
                        if (alreadyAdded.contains(member.toString())) continue;

                        // If type is a DeclaredType, check accessibility of member
                        if (t instanceof DeclaredType) {
                            if (trees.isAccessible(scope, member, (DeclaredType) t)) {
                                result.add(Completion.ofElement(member));
                            }
                        }
                        // Otherwise, accessibility rules are very complicated
                        // Give up and just declare that everything is accessible
                        else result.add(Completion.ofElement(member));
                        // Remember the signature of the added method, so we don't re-add it later
                        alreadyAdded.add(member.toString());
                    }
                }
                return result;
            } else {
                LOG.warning("Don't know how to complete members of type " + type);
                return Collections.emptyList();
            }
        }
    }

    /**
     * Complete members or identifiers at the cursor. Delegates to `members` or `scopeMembers`, depending on whether the
     * expression before the cursor looks like `foo.bar` or `foo`
     */
    public CompletionResult completions(URI file, String contents, int line, int character, int limitHint) {
        LOG.info(String.format("Completing at %s[%d,%d]...", file.getPath(), line, character));
        // TODO why not just recompile? It's going to get triggered shortly anyway
        JavacTask task = singleFileTask(file, contents);
        CompilationUnitTree parse;
        try {
            parse = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = parse.getLineMap();
        long cursor = lines.getPosition(line, character);

        class Find extends TreeScanner<Void, Void> {
            List<Completion> result = null;
            boolean isIncomplete = false;

            boolean containsCursor(Tree node) {
                return pos.getStartPosition(parse, node) <= cursor && cursor <= pos.getEndPosition(parse, node);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void nothing) {
                super.visitMemberSelect(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getExpression()) && result == null) {
                    LOG.info("...completing members of " + node.getExpression());
                    long offset = pos.getEndPosition(parse, node.getExpression()),
                            line = lines.getLineNumber(offset),
                            column = lines.getColumnNumber(offset);
                    result = members(file, contents, (int) line, (int) column);
                }
                return null;
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void nothing) {
                super.visitMemberReference(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getQualifierExpression()) && result == null) {
                    LOG.info("...completing members of " + node.getQualifierExpression());
                    long offset = pos.getEndPosition(parse, node.getQualifierExpression()),
                            line = lines.getLineNumber(offset),
                            column = lines.getColumnNumber(offset);
                    result = members(file, contents, (int) line, (int) column);
                }
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                super.visitIdentifier(node, nothing);

                if (containsCursor(node) && result == null) {
                    LOG.info("...completing identifiers");
                    result = new ArrayList<>();
                    // Does a candidate completion match the name in `node`?
                    String id = Objects.toString(node.getName(), "");
                    Predicate<CharSequence> matches = name -> name.toString().startsWith(id);
                    Set<String> alreadyImported = new HashSet<>();
                    // Add names that have already been imported
                    for (Element m : scopeMembers(file, contents, line, character)) {
                        if (matches.test(m.getSimpleName())) {
                            result.add(Completion.ofElement(m));

                            if (m instanceof TypeElement) {
                                TypeElement t = (TypeElement) m;
                                alreadyImported.add(t.getQualifiedName().toString());
                            }
                        }
                    }
                    // Add names of classes that haven't been imported
                    String packageName = parse.getPackageName().toString();
                    Iterator<Completion> notImported =
                            classes.topLevelClasses()
                                    // This is very cheap, so we do it first
                                    .filter(c -> matches.test(c.getSimpleName()))
                                    .filter(c -> !alreadyImported.contains(c.getName()))
                                    // This is very expensive, so we do it last
                                    .filter(c -> classes.isAccessibleFromPackage(c, packageName))
                                    .map(Completion::ofNotImportedClass)
                                    .iterator();
                    while (notImported.hasNext() && result.size() < limitHint) {
                        result.add(notImported.next());
                    }
                    isIncomplete = notImported.hasNext();
                }
                return null;
            }

            CompletionResult run() {
                scan(parse, null);
                if (result == null) result = Collections.emptyList();
                if (isIncomplete) LOG.info(String.format("Found %d items (incomplete)", result.size()));
                return new CompletionResult(result, isIncomplete);
            }
        }
        return new Find().run();
    }

    /** Find all overloads for the smallest method call that includes the cursor */
    public Optional<MethodInvocation> methodInvocation(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        TreePath start = path(file, line, character);

        for (TreePath path = start; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof MethodInvocationTree) {
                MethodInvocationTree invoke = (MethodInvocationTree) path.getLeaf();
                Element method = trees.getElement(trees.getPath(path.getCompilationUnit(), invoke.getMethodSelect()));
                List<ExecutableElement> results = new ArrayList<>();
                for (Element m : method.getEnclosingElement().getEnclosedElements()) {
                    if (m.getKind() == ElementKind.METHOD && m.getSimpleName().equals(method.getSimpleName())) {
                        results.add((ExecutableElement) m);
                    }
                }
                int activeParameter = invoke.getArguments().indexOf(start.getLeaf());
                Optional<ExecutableElement> activeMethod =
                        method instanceof ExecutableElement
                                ? Optional.of((ExecutableElement) method)
                                : Optional.empty();
                return Optional.of(new MethodInvocation(invoke, activeMethod, activeParameter, results));
            } else if (path.getLeaf() instanceof NewClassTree) {
                NewClassTree invoke = (NewClassTree) path.getLeaf();
                Element method = trees.getElement(path);
                List<ExecutableElement> results = new ArrayList<>();
                for (Element m : method.getEnclosingElement().getEnclosedElements()) {
                    if (m.getKind() == ElementKind.CONSTRUCTOR) {
                        results.add((ExecutableElement) m);
                    }
                }
                int activeParameter = invoke.getArguments().indexOf(start.getLeaf());
                Optional<ExecutableElement> activeMethod =
                        method instanceof ExecutableElement
                                ? Optional.of((ExecutableElement) method)
                                : Optional.empty();
                return Optional.of(new MethodInvocation(invoke, activeMethod, activeParameter, results));
            }
        }
        return Optional.empty();
    }

    /** Find the smallest element that includes the cursor */
    public Element element(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        TreePath path = path(file, line, character);
        return trees.getElement(path);
    }

    public Optional<TreePath> definition(URI file, String contents, int line, int character) {
        recompile(file, contents, -1, -1);

        Trees trees = Trees.instance(cache.task);
        TreePath path = path(file, line, character);
        Element e = trees.getElement(path);
        TreePath t = trees.getPath(e);
        return Optional.ofNullable(t);
    }

    /** Look up the javadoc associated with `e` */
    public Optional<MethodDoc> methodDoc(ExecutableElement e) {
        return docs.methodDoc(e);
    }

    /** Look up the javadoc associated with `e` */
    public Optional<ClassDoc> classDoc(TypeElement e) {
        return docs.classDoc(e);
    }

    private Stream<Path> javaSourcesInDir(Path dir) {
        PathMatcher match = FileSystems.getDefault().getPathMatcher("glob:*.java");

        try {
            // TODO instead of looking at EVERY file, once you see a few files with the same source directory,
            // ignore all subsequent files in the directory
            return Files.walk(dir).filter(java -> match.matches(java.getFileName()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Stream<Path> javaSources() {
        return sourcePath.stream().flatMap(dir -> javaSourcesInDir(dir));
    }

    private List<Path> potentialReferences(Element to) {
        String name = to.getSimpleName().toString();
        Pattern word = Pattern.compile("\\b\\w+\\b");
        Predicate<String> containsWord =
                line -> {
                    Matcher m = word.matcher(line);
                    while (m.find()) {
                        if (m.group().equals(name)) return true;
                    }
                    return false;
                };
        Predicate<Path> test =
                file -> {
                    try {
                        return Files.readAllLines(file).stream().anyMatch(containsWord);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
        return javaSources().filter(test).collect(Collectors.toList());
    }

    /**
     * Represents a batch compilation of many files. The batch context is different that the incremental context, so
     * methods in this class should not access `cache`.
     */
    static class Batch {
        final JavacTask task;
        final List<CompilationUnitTree> roots;

        Batch(JavacTask task, List<CompilationUnitTree> roots) {
            this.task = task;
            this.roots = roots;
        }

        private boolean toStringEquals(Object left, Object right) {
            return Objects.equals(Objects.toString(left, ""), Objects.toString(right, ""));
        }

        private boolean sameSymbol(Element target, Element symbol) {
            return symbol != null
                    && target != null
                    && toStringEquals(symbol.getEnclosingElement(), target.getEnclosingElement())
                    && toStringEquals(symbol, target);
        }

        private List<TreePath> actualReferences(CompilationUnitTree from, Element to) {
            Trees trees = Trees.instance(task);

            class Finder extends TreeScanner<Void, Void> {
                List<TreePath> results = new ArrayList<>();

                @Override
                public Void scan(Tree leaf, Void nothing) {
                    if (leaf != null) {
                        TreePath path = trees.getPath(from, leaf);
                        Element found = trees.getElement(path);

                        if (sameSymbol(found, to)) results.add(path);
                        else super.scan(leaf, nothing);
                    }
                    return null;
                }

                List<TreePath> run() {
                    scan(from, null);
                    return results;
                }
            }
            return new Finder().run();
        }
    }

    private Batch compileBatch(List<Path> files) {
        JavacTask task = batchTask(files);

        List<CompilationUnitTree> result = new ArrayList<>();
        try {
            for (CompilationUnitTree t : task.parse()) result.add(t);
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return new Batch(task, result);
    }

    public List<TreePath> references(URI file, String contents, int line, int character) {
        recompile(file, contents, -1, -1);

        Trees trees = Trees.instance(cache.task);
        TreePath path = path(file, line, character);
        Element to = trees.getElement(path);
        List<Path> possible = potentialReferences(to);
        Batch batch = compileBatch(possible);
        List<TreePath> result = new ArrayList<>();
        for (CompilationUnitTree f : batch.roots) {
            result.addAll(batch.actualReferences(f, to));
        }
        return result;
    }

    public Stream<TreePath> findSymbols(String query) {
        return sourcePath.stream().flatMap(dir -> Parser.findSymbols(dir, query));
    }

    // TODO this is ugly, suggests something needs to be moved into JavaCompilerService
    public Trees trees() {
        return Trees.instance(cache.task);
    }

    /**
     * Figure out what imports this file should have. Star-imports like `import java.util.*` are converted to individual
     * class imports. Missing imports are inferred by looking at imports in other source files.
     */
    public Set<String> fixImports(URI file, String contents) {
        // Compile a single file
        JavacTask task = singleFileTask(file, contents);
        CompilationUnitTree tree;
        try {
            tree = task.parse().iterator().next();
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // Check diagnostics for missing imports
        Set<String> unresolved = new HashSet<>();
        for (Diagnostic<? extends JavaFileObject> d : diags) {
            if (d.getCode().equals("compiler.err.cant.resolve.location") && d.getSource().toUri().equals(file)) {
                long start = d.getStartPosition(), end = d.getEndPosition();
                String id = contents.substring((int) start, (int) end);
                if (id.matches("[A-Z]\\w+")) {
                    unresolved.add(id);
                }
            }
        }
        // Look at imports in other classes to help us guess how to fix imports
        FixImports fix = new FixImports(classes);
        FixImports.ExistingImports sourcePathImports = fix.existingImports(sourcePath);
        Map<String, String> fixes = fix.resolveSymbols(unresolved, sourcePathImports);
        // Figure out which existing imports are actually used
        Trees trees = Trees.instance(task);
        Set<String> qualifiedNames = new HashSet<>();
        class FindUsedImports extends TreePathScanner<Void, Void> {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                Element e = trees.getElement(getCurrentPath());
                if (e instanceof TypeElement) {
                    TypeElement t = (TypeElement) e;
                    String qualifiedName = t.getQualifiedName().toString();
                    int lastDot = qualifiedName.lastIndexOf('.');
                    String packageName = lastDot == -1 ? "" : qualifiedName.substring(0, lastDot);
                    String thisPackage = Objects.toString(tree.getPackageName(), "");
                    // java.lang.* and current package are imported by default
                    if (!packageName.equals("java.lang")
                            && !packageName.equals(thisPackage)
                            && !packageName.equals("")) {
                        qualifiedNames.add(qualifiedName);
                    }
                }
                return null;
            }
        }
        new FindUsedImports().scan(tree, null);
        // Add qualified names from fixes
        qualifiedNames.addAll(fixes.values());
        return qualifiedNames;
    }
}

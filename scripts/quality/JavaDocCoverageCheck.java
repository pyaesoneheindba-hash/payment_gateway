import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Verifies project-wide JavaDoc coverage by parsing Java sources with the JDK compiler API.
 *
 * <p>The check requires JavaDoc on every named class, interface, enum, record, annotation
 * type, constructor and explicitly declared method. Anonymous classes are not themselves
 * documentable declarations, but methods declared inside them are still checked.</p>
 */
public final class JavaDocCoverageCheck {
    /**
     * Prevents instantiation of this utility class.
     */
    private JavaDocCoverageCheck() {
    }

    /**
     * Runs the JavaDoc coverage check for the supplied project root.
     *
     * @param args optional first argument containing the project root; defaults to the current directory
     * @throws Exception if source discovery or Java parsing fails
     */
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A full JDK is required to verify JavaDoc coverage");
        }

        List<Path> javaFiles = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(javaFiles::add);
        }
        Collections.sort(javaFiles);

        List<String> missing = new ArrayList<>();
        for (Path source : javaFiles) {
            checkFile(root, source, compiler, missing);
        }
        if (!missing.isEmpty()) {
            System.err.println("JavaDoc coverage verification failed:");
            missing.forEach(item -> System.err.println(" - " + item));
            System.exit(1);
        }
        System.out.println("JavaDoc coverage PASS: " + javaFiles.size() + " Java source files checked");
    }

    /**
     * Parses one source file and records declarations that do not have JavaDoc.
     *
     * @param root project root used to render relative paths
     * @param source Java source file to inspect
     * @param compiler system Java compiler used only for parsing
     * @param missing mutable collection receiving missing-documentation findings
     * @throws Exception if the source cannot be parsed
     */
    private static void checkFile(
            Path root,
            Path source,
            JavaCompiler compiler,
            List<String> missing) throws Exception {
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var objects = manager.getJavaFileObjects(source.toFile());
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, objects);
            DocTrees docs = DocTrees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                new TreePathScanner<Void, String>() {
                    /**
                     * Checks JavaDoc coverage for a named type declaration.
                     *
                     * @param node type declaration being visited
                     * @param owner enclosing named type, when present
                     * @return always {@code null}
                     */
                    @Override
                    public Void visitClass(ClassTree node, String owner) {
                        String name = node.getSimpleName().toString();
                        if (!name.isBlank() && !hasDoc(docs, getCurrentPath())) {
                            missing.add(display(root, source) + ": type " + qualified(owner, name));
                        }
                        String nextOwner = name.isBlank() ? owner : qualified(owner, name);
                        return super.visitClass(node, nextOwner);
                    }

                    /**
                     * Checks JavaDoc coverage for an explicitly declared method or constructor.
                     *
                     * @param node method or constructor declaration being visited
                     * @param owner enclosing named type, when present
                     * @return always {@code null}
                     */
                    @Override
                    public Void visitMethod(MethodTree node, String owner) {
                        if (!hasDoc(docs, getCurrentPath())) {
                            String methodName = node.getName().contentEquals("<init>")
                                    ? simpleOwner(owner)
                                    : node.getName().toString();
                            missing.add(display(root, source) + ": method " + qualified(owner, methodName));
                        }
                        return super.visitMethod(node, owner);
                    }
                }.scan(unit, "");
            }
        }
    }

    /**
     * Determines whether the declaration represented by a tree path has JavaDoc.
     *
     * @param docs JavaDoc tree utility
     * @param path declaration path to inspect
     * @return {@code true} when a JavaDoc comment is attached to the declaration
     */
    private static boolean hasDoc(DocTrees docs, TreePath path) {
        try {
            DocCommentTree comment = docs.getDocCommentTree(path);
            return comment != null;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Joins an enclosing type name and member name for diagnostic output.
     *
     * @param owner enclosing type name
     * @param name declaration name
     * @return qualified diagnostic name
     */
    private static String qualified(String owner, String name) {
        return owner == null || owner.isBlank() ? name : owner + "." + name;
    }

    /**
     * Returns the simple type name from a dotted owner path.
     *
     * @param owner dotted owner path
     * @return simple owner name, or {@code <init>} when no owner is available
     */
    private static String simpleOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return "<init>";
        }
        int separator = owner.lastIndexOf('.');
        return separator < 0 ? owner : owner.substring(separator + 1);
    }

    /**
     * Renders a source path relative to the checked project root when possible.
     *
     * @param root project root
     * @param source source file path
     * @return normalized relative source path
     */
    private static String display(Path root, Path source) {
        try {
            return root.relativize(source).toString().replace('\\', '/');
        }
        catch (IllegalArgumentException ignored) {
            return source.toString();
        }
    }
}

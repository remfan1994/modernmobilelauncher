import java.io.File;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class JavaSyntaxSelfTest {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            throw new AssertionError("Pass at least one Java source path.");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("A JDK compiler is required.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> sources = files.getJavaFileObjectsFromFiles(
                    java.util.Arrays.stream(arguments).map(File::new).toList());
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, files, diagnostics, List.of("-proc:none"), null, sources);
            ((com.sun.source.util.JavacTask) task).parse();
        }
        StringBuilder failures = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                failures.append(diagnostic).append('\n');
            }
        }
        if (failures.length() > 0) {
            throw new AssertionError(failures.toString());
        }
        System.out.println("Java syntax self-test passed for " + arguments.length + " source files.");
    }
}


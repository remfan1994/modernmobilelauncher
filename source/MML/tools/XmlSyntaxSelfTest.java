import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

public final class XmlSyntaxSelfTest {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            throw new AssertionError("Pass at least one XML resource path.");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (String argument : arguments) {
            Path source = Path.of(argument);
            try (var input = Files.newInputStream(source)) {
                factory.newDocumentBuilder().parse(input);
            }
        }
        System.out.println("XML syntax self-test passed for " + arguments.length + " files.");
    }
}

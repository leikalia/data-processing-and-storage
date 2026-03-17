package boze.pomogi.shabalina;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("java ... <input.xml> [output-folder]");
            return;
        }

        Path inputFile = Path.of(args[0]);
        Path outputFolder;

        if (args.length >= 2) {
            outputFolder = Path.of(args[1]);
        } else {
            outputFolder = Path.of("build/generated-output");
        }

        XmlPersonParser parser = new XmlPersonParser();
        List<Person> rawPeople = parser.parse(inputFile);

        XmlNormalizer normalizer = new XmlNormalizer();
        Map<String, Person> result = normalizer.normalize(rawPeople);

        Xml2Writer writer = new Xml2Writer();
        writer.writeResult(result, outputFolder, normalizer.getIssues());

        System.out.println("Done.");
        System.out.println("Raw records: " + rawPeople.size());
        System.out.println("Unique people after merge: " + result.size());
        System.out.println("Output folder: " + outputFolder.toAbsolutePath());
    }
}

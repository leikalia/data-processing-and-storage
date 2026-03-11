package boze.pomogi.shabalina;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class XmlTask1Test {

    @Test
    void testPipeline() throws Exception {
        Path input = Path.of("src/test/resources/sample-input.xml");
        Path output = Files.createTempDirectory("xml-task1-test");

        XmlPersonParser parser = new XmlPersonParser();
        List<Person> rawPeople = parser.parse(input);

        XmlNormalizer normalizer = new XmlNormalizer();
        Map<String, Person> people = normalizer.normalize(rawPeople);
        normalizer.writeResult(people, output);

        assertEquals(4, rawPeople.size());
        assertEquals(4, people.size());

        assertTrue(Files.exists(output.resolve("people-normalized.xml")));
        assertTrue(Files.exists(output.resolve("consistency-report.txt")));

        String xmlText = Files.readString(output.resolve("people-normalized.xml"));
        assertTrue(xmlText.contains("<person id=\"P1\">"));
    }
}


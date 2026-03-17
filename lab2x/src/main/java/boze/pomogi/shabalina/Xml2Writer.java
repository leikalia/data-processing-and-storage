package boze.pomogi.shabalina;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Xml2Writer {

    public void writeResult(Map<String, Person> peopleByKey,
                            Path outputFolder,
                            List<String> issues) throws Exception {
        Files.createDirectories(outputFolder);

        Path xmlFile = outputFolder.resolve("people-strict.xml");
        Path reportFile = outputFolder.resolve("consistency-report.txt");
        Path xsdCopyFile = outputFolder.resolve("people.xsd");

        PeopleXml root = buildJaxbModel(peopleByKey);
        Schema schema = loadSchemaFromResources();

        marshalWithValidation(root, xmlFile, schema);
        writeReport(reportFile, issues);
        copySchemaToOutput(xsdCopyFile);
    }

    private PeopleXml buildJaxbModel(Map<String, Person> peopleByKey) {
        PeopleXml root = new PeopleXml();

        List<Person> sortedPeople = new ArrayList<>(peopleByKey.values());
        sortedPeople.sort(Comparator.comparing(p -> p.id));

        Map<String, PersonXml> xmlById = new LinkedHashMap<>();

        for (Person person : sortedPeople) {
            PersonXml xml = new PersonXml();
            xml.id = person.id;
            xml.firstName = blankToNull(person.firstName);
            xml.lastName = blankToNull(person.lastName);
            xml.gender = blankToNull(person.gender);

            List<String> aliases = new ArrayList<>(person.aliases);
            Collections.sort(aliases);
            xml.aliases.addAll(aliases);

            root.persons.add(xml);
            xmlById.put(xml.id, xml);
        }

        for (Person person : sortedPeople) {
            PersonXml xml = xmlById.get(person.id);

            addRefs(xml.fathers, person.fathers, xmlById);
            addRefs(xml.mothers, person.mothers, xmlById);
            addRefs(xml.parents, person.parents, xmlById);

            addRefs(xml.spouses, person.spouses, xmlById);

            addRefs(xml.sons, person.sons, xmlById);
            addRefs(xml.daughters, person.daughters, xmlById);
            addRefs(xml.children, person.children, xmlById);

            addRefs(xml.brothers, person.brothers, xmlById);
            addRefs(xml.sisters, person.sisters, xmlById);
            addRefs(xml.siblings, person.siblings, xmlById);
        }

        return root;
    }

    private void addRefs(List<RefXml> targetList,
                         Set<String> ids,
                         Map<String, PersonXml> xmlById) {
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);

        for (String id : sorted) {
            PersonXml target = xmlById.get(id);
            if (target != null) {
                targetList.add(new RefXml(target));
            }
        }
    }

    private void marshalWithValidation(PeopleXml root,
                                       Path xmlFile,
                                       Schema schema) throws Exception {
        JAXBContext context = JAXBContext.newInstance(PeopleXml.class, PersonXml.class, RefXml.class);
        Marshaller marshaller = context.createMarshaller();

        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        marshaller.setSchema(schema);

        try (var out = Files.newOutputStream(xmlFile)) {
            marshaller.marshal(root, out);
        }
    }

    private Schema loadSchemaFromResources() throws Exception {
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("people.xsd")) {
            if (in == null) {
                throw new IllegalStateException("people.xsd not found in resources");
            }
            return schemaFactory.newSchema(new StreamSource(in));
        }
    }

    private void copySchemaToOutput(Path xsdCopyFile) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("people.xsd")) {
            if (in == null) {
                throw new IllegalStateException("people.xsd not found in resources");
            }
            Files.copy(in, xsdCopyFile);
        }
    }

    private void writeReport(Path file, List<String> issues) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            if (issues.isEmpty()) {
                writer.write("No consistency issues found.");
                writer.newLine();
                return;
            }

            for (String issue : issues) {
                writer.write(issue);
                writer.newLine();
            }
        }
    }

    private String blankToNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }
}


package boze.pomogi.shabalina;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class XmlPersonParser {

    public List<Person> parse(Path file) throws Exception {
        List<Person> people = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);

        try (InputStream inputStream = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(inputStream);

            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT &&
                        reader.getLocalName().equals("person")) {

                    Person person = readPerson(reader);
                    people.add(person);
                }
            }

            reader.close();
        }

        return people;
    }

    private Person readPerson(XMLStreamReader reader) throws Exception {
        Person person = new Person();

        person.id = clean(reader.getAttributeValue(null, "id"));

        String nameAttr = clean(reader.getAttributeValue(null, "name"));
        if (nameAttr != null) {
            fillNameFromText(person, nameAttr);
        }

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();

                switch (tag) {
                    case "id" -> person.id = firstNotEmpty(person.id, readSimpleValue(reader));

                    case "firstname" -> person.firstName = firstNotEmpty(person.firstName, readSimpleValue(reader));

                    case "surname", "family-name" -> person.lastName = firstNotEmpty(person.lastName, readSimpleValue(reader));

                    case "gender" -> {
                        String value = readSimpleValue(reader);
                        if (value != null) {
                            person.gender = normalizeGender(value);
                        }
                    }

                    case "children-number" -> person.expectedChildrenCount = parseInt(readSimpleValue(reader));
                    case "siblings-number" -> person.expectedSiblingsCount = parseInt(readSimpleValue(reader));

                    case "father" -> person.rawRelations.add(new Relation("father", readSimpleValue(reader)));
                    case "mother" -> person.rawRelations.add(new Relation("mother", readSimpleValue(reader)));
                    case "parent" -> person.rawRelations.add(new Relation("parent", readSimpleValue(reader)));

                    case "spouce", "spouse" -> person.rawRelations.add(new Relation("spouse", readSimpleValue(reader)));
                    case "husband" -> person.rawRelations.add(new Relation("husband", readSimpleValue(reader)));
                    case "wife" -> person.rawRelations.add(new Relation("wife", readSimpleValue(reader)));

                    case "brother" -> person.rawRelations.add(new Relation("brother", readSimpleValue(reader)));
                    case "sister" -> person.rawRelations.add(new Relation("sister", readSimpleValue(reader)));

                    case "siblings" -> readManyValues(reader, person, "sibling");

                    case "children" -> readChildren(reader, person);

                    case "fullname" -> readFullName(reader, person);

                    default -> skipTag(reader);
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT &&
                    reader.getLocalName().equals("person")) {
                break;
            }
        }

        String fullName = person.getFullName();
        if (!fullName.isBlank()) {
            person.aliases.add(fullName);
        }

        return person;
    }

    private void readFullName(XMLStreamReader reader, Person person) throws Exception {
        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();

                if (tag.equals("first")) {
                    person.firstName = firstNotEmpty(person.firstName, readText(reader));
                } else if (tag.equals("family")) {
                    person.lastName = firstNotEmpty(person.lastName, readText(reader));
                } else {
                    skipTag(reader);
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT &&
                    reader.getLocalName().equals("fullname")) {
                break;
            }
        }
    }

    private void readChildren(XMLStreamReader reader, Person person) throws Exception {
        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();

                if (tag.equals("son")) {
                    person.rawRelations.add(new Relation("son", readChildValue(reader)));
                } else if (tag.equals("daughter")) {
                    person.rawRelations.add(new Relation("daughter", readChildValue(reader)));
                } else if (tag.equals("child")) {
                    person.rawRelations.add(new Relation("child", readChildValue(reader)));
                } else {
                    skipTag(reader);
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT &&
                    reader.getLocalName().equals("children")) {
                break;
            }
        }
    }

    private void readManyValues(XMLStreamReader reader, Person person, String relationType) throws Exception {
        String value = reader.getAttributeValue(null, "val");

        if (value == null) {
            value = readText(reader);
        } else {
            skipTag(reader);
        }

        value = clean(value);
        if (value == null) {
            return;
        }

        String[] parts = value.split("\\s+");
        for (String part : parts) {
            String cleaned = clean(part);
            if (cleaned != null) {
                person.rawRelations.add(new Relation(relationType, cleaned));
            }
        }
    }

    private String readChildValue(XMLStreamReader reader) throws Exception {
        String id = clean(reader.getAttributeValue(null, "id"));
        String value = clean(reader.getAttributeValue(null, "value"));

        if (id != null) {
            skipTag(reader);
            return id;
        }

        if (value != null) {
            skipTag(reader);
            return value;
        }

        return readText(reader);
    }

    private String readSimpleValue(XMLStreamReader reader) throws Exception {
        String value1 = clean(reader.getAttributeValue(null, "value"));
        String value2 = clean(reader.getAttributeValue(null, "val"));

        if (value1 != null) {
            skipTag(reader);
            return value1;
        }

        if (value2 != null) {
            skipTag(reader);
            return value2;
        }

        return readText(reader);
    }

    private String readText(XMLStreamReader reader) throws Exception {
        StringBuilder sb = new StringBuilder();

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.CHARACTERS ||
                    event == XMLStreamConstants.CDATA) {
                sb.append(reader.getText());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                skipTag(reader);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }

        return clean(sb.toString());
    }

    private void skipTag(XMLStreamReader reader) throws Exception {
        int level = 1;

        while (reader.hasNext() && level > 0) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                level++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                level--;
            }
        }
    }

    private void fillNameFromText(Person person, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] parts = text.trim().split("\\s+", 2);

        person.firstName = firstNotEmpty(person.firstName, parts[0]);

        if (parts.length > 1) {
            person.lastName = firstNotEmpty(person.lastName, parts[1]);
        }
    }

    private Integer parseInt(String text) {
        if (text == null) {
            return null;
        }

        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeGender(String text) {
        String s = clean(text);
        if (s == null) {
            return "unknown";
        }

        s = s.toLowerCase();

        if (s.equals("m") || s.equals("male")) {
            return "male";
        }
        if (s.equals("f") || s.equals("female")) {
            return "female";
        }

        return "unknown";
    }

    private String clean(String text) {
        if (text == null) {
            return null;
        }

        String result = text.replace('\u00A0', ' ').trim();
        result = result.replaceAll("\\s+", " ");

        if (result.isBlank()) {
            return null;
        }

        return result;
    }

    private String firstNotEmpty(String oldValue, String newValue) {
        if (oldValue == null || oldValue.isBlank()) {
            return newValue;
        }
        return oldValue;
    }
}

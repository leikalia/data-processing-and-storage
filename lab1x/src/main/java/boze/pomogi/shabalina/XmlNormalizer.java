package boze.pomogi.shabalina;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class XmlNormalizer {

    private final List<String> issues = new ArrayList<>();
    private int generatedIdCounter = 1;

    public Map<String, Person> normalize(List<Person> rawPeople) {
        Map<String, Person> peopleByKey = mergeDuplicates(rawPeople);
        connectPeople(peopleByKey);
        improveRelationsUsingGender(peopleByKey);
        checkConsistency(peopleByKey);
        return peopleByKey;
    }

    private Map<String, Person> mergeDuplicates(List<Person> rawPeople) {
        Map<String, Person> result = new LinkedHashMap<>();
        Map<String, String> idToKey = new HashMap<>();
        Map<String, List<String>> nameToKeys = new HashMap<>();

        for (Person raw : rawPeople) {
            String key = findExistingKey(raw, result, idToKey, nameToKeys);

            if (key == null) {
                key = makePersonKey(raw);
                Person person = copyRawPerson(raw);
                result.put(key, person);
                registerPerson(person, key, idToKey, nameToKeys);
            } else {
                Person existing = result.get(key);
                mergePersonData(existing, raw);

                unregisterPerson(existing, key, idToKey, nameToKeys);
                registerPerson(existing, key, idToKey, nameToKeys);
            }
        }

        for (Person person : result.values()) {
            if (person.id == null || person.id.isBlank()) {
                person.id = "GEN_" + String.format("%05d", generatedIdCounter++);
            }
        }

        return result;
    }

    private void registerPerson(Person person,
                                String key,
                                Map<String, String> idToKey,
                                Map<String, List<String>> nameToKeys) {
        if (person.id != null && !person.id.isBlank()) {
            idToKey.put(person.id, key);
        }

        String fullName = normalizeName(person.getFullName());
        if (fullName != null) {
            nameToKeys.computeIfAbsent(fullName, k -> new ArrayList<>());
            List<String> keys = nameToKeys.get(fullName);
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
    }

    private void unregisterPerson(Person person,
                                  String key,
                                  Map<String, String> idToKey,
                                  Map<String, List<String>> nameToKeys) {
        if (person.id != null && !person.id.isBlank()) {
            idToKey.put(person.id, key);
        }

        String fullName = normalizeName(person.getFullName());
        if (fullName != null && nameToKeys.containsKey(fullName)) {
            List<String> keys = nameToKeys.get(fullName);
            keys.removeIf(k -> k.equals(key));
            if (keys.isEmpty()) {
                nameToKeys.remove(fullName);
            }
        }
    }

    private String findExistingKey(Person raw,
                                   Map<String, Person> result,
                                   Map<String, String> idToKey,
                                   Map<String, List<String>> nameToKeys) {
        if (raw.id != null && idToKey.containsKey(raw.id)) {
            return idToKey.get(raw.id);
        }

        String fullName = normalizeName(raw.getFullName());
        if (fullName == null || !nameToKeys.containsKey(fullName)) {
            return null;
        }

        List<String> candidateKeys = nameToKeys.get(fullName);
        for (String key : candidateKeys) {
            Person candidate = result.get(key);
            if (candidate != null && canMergeByName(candidate, raw)) {
                return key;
            }
        }

        return null;
    }

    private boolean canMergeByName(Person existing, Person incoming) {
        if (!isBlank(existing.id) && !isBlank(incoming.id) && !existing.id.equals(incoming.id)) {
            return false;
        }

        if (!isBlank(existing.id) || !isBlank(incoming.id)) {
            return true;
        }

        if (!isUnknown(existing.gender) && !isUnknown(incoming.gender) && !existing.gender.equals(incoming.gender)) {
            return false;
        }

        boolean sameGenderOrUnknown = isUnknown(existing.gender)
                || isUnknown(incoming.gender)
                || existing.gender.equals(incoming.gender);

        boolean bothHaveSurname = !isBlank(existing.lastName) && !isBlank(incoming.lastName);
        boolean bothHaveFirstName = !isBlank(existing.firstName) && !isBlank(incoming.firstName);

        int evidenceScore = 0;

        if (sameGenderOrUnknown) {
            evidenceScore++;
        }

        if (existing.expectedChildrenCount != null && incoming.expectedChildrenCount != null
                && existing.expectedChildrenCount.equals(incoming.expectedChildrenCount)) {
            evidenceScore++;
        }

        if (existing.expectedSiblingsCount != null && incoming.expectedSiblingsCount != null
                && existing.expectedSiblingsCount.equals(incoming.expectedSiblingsCount)) {
            evidenceScore++;
        }

        if (!existing.rawRelations.isEmpty() && !incoming.rawRelations.isEmpty()) {
            evidenceScore++;
        }

        if (!existing.aliases.isEmpty() && !incoming.aliases.isEmpty()) {
            evidenceScore++;
        }

        // Conservative rule:
        // for id-less records, same name alone is not enough.
        // Need at least some additional evidence.
        return bothHaveFirstName && bothHaveSurname && evidenceScore >= 2;
    }

    private String makePersonKey(Person p) {
        if (p.id != null && !p.id.isBlank()) {
            return "ID:" + p.id;
        }

        String fullName = normalizeName(p.getFullName());
        if (fullName != null) {
            return "NAME:" + fullName + "#" + UUID.randomUUID();
        }

        return "TEMP:" + UUID.randomUUID();
    }

    private Person copyRawPerson(Person raw) {
        Person p = new Person();
        p.id = clean(raw.id);
        p.firstName = clean(raw.firstName);
        p.lastName = clean(raw.lastName);
        p.gender = clean(raw.gender);
        if (p.gender == null) {
            p.gender = "unknown";
        }

        p.expectedChildrenCount = raw.expectedChildrenCount;
        p.expectedSiblingsCount = raw.expectedSiblingsCount;

        p.rawRelations.addAll(raw.rawRelations);
        p.aliases.addAll(raw.aliases);

        String fullName = clean(p.getFullName());
        if (fullName != null) {
            p.aliases.add(fullName);
        }

        return p;
    }

    private void mergePersonData(Person target, Person source) {
        if (isBlank(target.id) && !isBlank(source.id)) {
            target.id = source.id;
        }

        if (isBlank(target.firstName) && !isBlank(source.firstName)) {
            target.firstName = source.firstName;
        }

        if (isBlank(target.lastName) && !isBlank(source.lastName)) {
            target.lastName = source.lastName;
        }

        if (isUnknown(target.gender) && !isUnknown(source.gender)) {
            target.gender = source.gender;
        }

        if (source.expectedChildrenCount != null) {
            target.expectedChildrenCount = source.expectedChildrenCount;
        }

        if (source.expectedSiblingsCount != null) {
            target.expectedSiblingsCount = source.expectedSiblingsCount;
        }

        target.rawRelations.addAll(source.rawRelations);
        target.aliases.addAll(source.aliases);

        String sourceFullName = clean(source.getFullName());
        if (sourceFullName != null) {
            target.aliases.add(sourceFullName);
        }
    }

    private void connectPeople(Map<String, Person> peopleByKey) {
        Map<String, Person> byId = new HashMap<>();
        Map<String, List<Person>> byName = new HashMap<>();

        for (Person person : peopleByKey.values()) {
            if (!isBlank(person.id)) {
                byId.put(person.id, person);
            }

            String name = normalizeName(person.getFullName());
            if (name != null) {
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(person);
            }

            for (String alias : person.aliases) {
                String aliasKey = normalizeName(alias);
                if (aliasKey != null) {
                    byName.computeIfAbsent(aliasKey, k -> new ArrayList<>()).add(person);
                }
            }
        }

        for (Person source : peopleByKey.values()) {
            for (Relation relation : source.rawRelations) {
                Person target = resolveRelatedPerson(source, relation.value, byId, byName);

                if (target == null) {
                    String value = clean(relation.value);
                    if (!isIgnorableValue(value)) {
                        issues.add("Unresolved relation for " + source.id
                                + ": " + relation.type + " -> " + value);
                    }
                    continue;
                }

                if (source.id != null && source.id.equals(target.id)) {
                    issues.add("Self relation for " + source.id + ": " + relation.type);
                    continue;
                }

                switch (relation.type) {
                    case "father" -> {
                        source.fathers.add(target.id);
                        target.children.add(source.id);
                        if (isUnknown(target.gender)) {
                            target.gender = "male";
                        }
                    }
                    case "mother" -> {
                        source.mothers.add(target.id);
                        target.children.add(source.id);
                        if (isUnknown(target.gender)) {
                            target.gender = "female";
                        }
                    }
                    case "parent" -> {
                        source.parents.add(target.id);
                        target.children.add(source.id);
                    }
                    case "spouse", "husband", "wife" -> {
                        source.spouses.add(target.id);
                        target.spouses.add(source.id);

                        if ("husband".equals(relation.type) && isUnknown(target.gender)) {
                            target.gender = "male";
                        }
                        if ("wife".equals(relation.type) && isUnknown(target.gender)) {
                            target.gender = "female";
                        }
                    }
                    case "son" -> {
                        source.sons.add(target.id);
                        target.parents.add(source.id);
                        if (isUnknown(target.gender)) {
                            target.gender = "male";
                        }
                    }
                    case "daughter" -> {
                        source.daughters.add(target.id);
                        target.parents.add(source.id);
                        if (isUnknown(target.gender)) {
                            target.gender = "female";
                        }
                    }
                    case "child" -> {
                        source.children.add(target.id);
                        target.parents.add(source.id);
                    }
                    case "brother" -> {
                        source.brothers.add(target.id);
                        target.siblings.add(source.id);
                        if (isUnknown(target.gender)) {
                            target.gender = "male";
                        }
                    }
                    case "sister" -> {
                        source.sisters.add(target.id);
                        target.siblings.add(source.id);
                        if (isUnknown(target.gender)) {
                            target.gender = "female";
                        }
                    }
                    case "sibling" -> {
                        source.siblings.add(target.id);
                        target.siblings.add(source.id);
                    }
                    default -> issues.add("Unknown relation type for " + source.id + ": " + relation.type);
                }
            }
        }
    }

    private Person resolveRelatedPerson(Person source,
                                        String value,
                                        Map<String, Person> byId,
                                        Map<String, List<Person>> byName) {
        String cleaned = clean(value);
        if (isIgnorableValue(cleaned)) {
            return null;
        }

        if (byId.containsKey(cleaned)) {
            return byId.get(cleaned);
        }

        String nameKey = normalizeName(cleaned);
        if (nameKey == null || !byName.containsKey(nameKey)) {
            return null;
        }

        List<Person> candidates = byName.get(nameKey);
        if (candidates.isEmpty()) {
            return null;
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        for (Person candidate : candidates) {
            if (!isUnknown(source.gender) && !isUnknown(candidate.gender) && !source.gender.equals(candidate.gender)) {
                return candidate;
            }
        }

        return candidates.get(0);
    }

    private void improveRelationsUsingGender(Map<String, Person> peopleByKey) {
        Map<String, Person> byId = new HashMap<>();
        for (Person p : peopleByKey.values()) {
            byId.put(p.id, p);
        }

        for (Person p : peopleByKey.values()) {
            moveIdsByGender(p.parents, p.fathers, p.mothers, byId);
            moveIdsByGender(p.children, p.sons, p.daughters, byId);
            moveIdsByGender(p.siblings, p.brothers, p.sisters, byId);
        }
    }

    private void moveIdsByGender(Set<String> source,
                                 Set<String> males,
                                 Set<String> females,
                                 Map<String, Person> byId) {
        List<String> toRemove = new ArrayList<>();

        for (String id : source) {
            Person person = byId.get(id);
            if (person == null) {
                continue;
            }

            if ("male".equals(person.gender)) {
                males.add(id);
                toRemove.add(id);
            } else if ("female".equals(person.gender)) {
                females.add(id);
                toRemove.add(id);
            }
        }

        source.removeAll(toRemove);
    }

    private void checkConsistency(Map<String, Person> peopleByKey) {
        for (Person p : peopleByKey.values()) {
            int childrenCount = uniqueCount(p.children, p.sons, p.daughters);
            int siblingsCount = uniqueCount(p.siblings, p.brothers, p.sisters);

            if (p.expectedChildrenCount != null && p.expectedChildrenCount != childrenCount) {
                issues.add("Children count mismatch for " + p.id
                        + ": expected " + p.expectedChildrenCount
                        + ", found " + childrenCount);
            }

            if (p.expectedSiblingsCount != null && p.expectedSiblingsCount != siblingsCount) {
                issues.add("Siblings count mismatch for " + p.id
                        + ": expected " + p.expectedSiblingsCount
                        + ", found " + siblingsCount);
            }

            if (p.fathers.size() > 1) {
                issues.add("More than one father found for " + p.id);
            }

            if (p.mothers.size() > 1) {
                issues.add("More than one mother found for " + p.id);
            }
        }
    }

    @SafeVarargs
    private int uniqueCount(Set<String>... sets) {
        Set<String> all = new LinkedHashSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return all.size();
    }

    public void writeResult(Map<String, Person> peopleByKey, Path outputFolder) throws Exception {
        Files.createDirectories(outputFolder);

        Path xmlFile = outputFolder.resolve("people-normalized.xml");
        Path reportFile = outputFolder.resolve("consistency-report.txt");

        writeXml(peopleByKey, xmlFile);
        writeReport(reportFile);
    }

    private void writeXml(Map<String, Person> peopleByKey, Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document document = factory.newDocumentBuilder().newDocument();

        Element root = document.createElement("people");
        document.appendChild(root);

        List<Person> people = new ArrayList<>(peopleByKey.values());
        people.sort(Comparator.comparing(p -> p.id));

        for (Person p : people) {
            Element personTag = document.createElement("person");
            personTag.setAttribute("id", p.id);

            addText(document, personTag, "firstname", p.firstName);
            addText(document, personTag, "surname", p.lastName);
            addText(document, personTag, "gender", p.gender);

            addRefs(document, personTag, "fathers", "father", p.fathers);
            addRefs(document, personTag, "mothers", "mother", p.mothers);
            addRefs(document, personTag, "parents", "parent", p.parents);

            addRefs(document, personTag, "spouses", "spouse", p.spouses);

            addRefs(document, personTag, "sons", "son", p.sons);
            addRefs(document, personTag, "daughters", "daughter", p.daughters);
            addRefs(document, personTag, "children", "child", p.children);

            addRefs(document, personTag, "brothers", "brother", p.brothers);
            addRefs(document, personTag, "sisters", "sister", p.sisters);
            addRefs(document, personTag, "siblings", "sibling", p.siblings);

            if (!p.aliases.isEmpty()) {
                Element aliasesTag = document.createElement("aliases");
                List<String> aliases = new ArrayList<>(p.aliases);
                Collections.sort(aliases);
                for (String alias : aliases) {
                    addText(document, aliasesTag, "alias", alias);
                }
                personTag.appendChild(aliasesTag);
            }

            root.appendChild(personTag);
        }

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        transformer.transform(new DOMSource(document), new StreamResult(file.toFile()));
    }

    private void addText(Document document, Element parent, String tagName, String text) {
        if (isBlank(text)) {
            return;
        }

        Element tag = document.createElement(tagName);
        tag.setTextContent(text);
        parent.appendChild(tag);
    }

    private void addRefs(Document document, Element parent, String wrapperName, String itemName, Set<String> ids) {
        if (ids.isEmpty()) {
            return;
        }

        Element wrapper = document.createElement(wrapperName);

        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);

        for (String id : sorted) {
            Element item = document.createElement(itemName);
            item.setAttribute("ref", id);
            wrapper.appendChild(item);
        }

        parent.appendChild(wrapper);
    }

    private void writeReport(Path file) throws Exception {
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

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private boolean isUnknown(String text) {
        if (text == null) {
            return true;
        }

        String s = text.trim().toLowerCase();
        return s.equals("unknown") || s.equals("none") || s.equals("n/a");
    }

    private boolean isIgnorableValue(String text) {
        if (text == null) {
            return true;
        }

        String s = text.trim().toLowerCase();
        return s.isBlank()
                || s.equals("unknown")
                || s.equals("none")
                || s.equals("n/a")
                || s.equals("null");
    }

    private String normalizeName(String text) {
        String s = clean(text);
        if (s == null) {
            return null;
        }
        return s.toLowerCase();
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
}


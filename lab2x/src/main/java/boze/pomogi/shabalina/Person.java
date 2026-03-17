package boze.pomogi.shabalina;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Person {
    public String id;
    public String firstName;
    public String lastName;
    public String gender = "unknown";

    public Integer expectedChildrenCount;
    public Integer expectedSiblingsCount;

    public List<Relation> rawRelations = new ArrayList<>();

    public Set<String> aliases = new LinkedHashSet<>();

    public Set<String> fathers = new LinkedHashSet<>();
    public Set<String> mothers = new LinkedHashSet<>();
    public Set<String> parents = new LinkedHashSet<>();

    public Set<String> spouses = new LinkedHashSet<>();

    public Set<String> sons = new LinkedHashSet<>();
    public Set<String> daughters = new LinkedHashSet<>();
    public Set<String> children = new LinkedHashSet<>();

    public Set<String> brothers = new LinkedHashSet<>();
    public Set<String> sisters = new LinkedHashSet<>();
    public Set<String> siblings = new LinkedHashSet<>();

    public String getFullName() {
        String a = firstName == null ? "" : firstName.trim();
        String b = lastName == null ? "" : lastName.trim();
        return (a + " " + b).trim().replaceAll("\\s+", " ");
    }
}

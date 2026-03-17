package boze.pomogi.shabalina;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlID;

import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class PersonXml {

    @XmlAttribute(name = "id", required = true)
    @XmlID
    public String id;

    @XmlElement(name = "firstname")
    public String firstName;

    @XmlElement(name = "surname")
    public String lastName;

    @XmlElement(name = "gender")
    public String gender;

    @XmlElementWrapper(name = "fathers")
    @XmlElement(name = "father")
    public List<RefXml> fathers = new ArrayList<>();

    @XmlElementWrapper(name = "mothers")
    @XmlElement(name = "mother")
    public List<RefXml> mothers = new ArrayList<>();

    @XmlElementWrapper(name = "parents")
    @XmlElement(name = "parent")
    public List<RefXml> parents = new ArrayList<>();

    @XmlElementWrapper(name = "spouses")
    @XmlElement(name = "spouse")
    public List<RefXml> spouses = new ArrayList<>();

    @XmlElementWrapper(name = "sons")
    @XmlElement(name = "son")
    public List<RefXml> sons = new ArrayList<>();

    @XmlElementWrapper(name = "daughters")
    @XmlElement(name = "daughter")
    public List<RefXml> daughters = new ArrayList<>();

    @XmlElementWrapper(name = "children")
    @XmlElement(name = "child")
    public List<RefXml> children = new ArrayList<>();

    @XmlElementWrapper(name = "brothers")
    @XmlElement(name = "brother")
    public List<RefXml> brothers = new ArrayList<>();

    @XmlElementWrapper(name = "sisters")
    @XmlElement(name = "sister")
    public List<RefXml> sisters = new ArrayList<>();

    @XmlElementWrapper(name = "siblings")
    @XmlElement(name = "sibling")
    public List<RefXml> siblings = new ArrayList<>();

    @XmlElementWrapper(name = "aliases")
    @XmlElement(name = "alias")
    public List<String> aliases = new ArrayList<>();
}

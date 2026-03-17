package boze.pomogi.shabalina;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlIDREF;

@XmlAccessorType(XmlAccessType.FIELD)
public class RefXml {

    @XmlAttribute(name = "ref", required = true)
    @XmlIDREF
    public PersonXml ref;

    public RefXml() {
    }

    public RefXml(PersonXml ref) {
        this.ref = ref;
    }
}

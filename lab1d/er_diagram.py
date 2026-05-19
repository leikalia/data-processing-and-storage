from graphviz import Digraph

def build_canonical_er(filename="people_er_canonical"):
    dot = Digraph("ER", format="png")
    dot.attr(rankdir="LR", splines="polyline", nodesep="0.8", ranksep="1.0")
    dot.attr("node", fontname="Times New Roman", fontsize="12")
    dot.attr("edge", fontname="Times New Roman", fontsize="11")

    dot.node("Person", "Person", shape="rectangle")
    dot.node("Alias", "Alias", shape="rectangle")

    dot.node("p_id", "id", shape="ellipse")
    dot.node("p_firstname", "firstname", shape="ellipse")
    dot.node("p_surname", "surname", shape="ellipse")
    dot.node("p_gender", "gender", shape="ellipse")

    dot.edge("Person", "p_id")
    dot.edge("Person", "p_firstname")
    dot.edge("Person", "p_surname")
    dot.edge("Person", "p_gender")

    dot.node("a_value", "alias", shape="ellipse")
    dot.edge("Alias", "a_value")

    dot.node("has_alias", "has_alias", shape="diamond")
    dot.edge("Person", "has_alias", label="1")
    dot.edge("has_alias", "Alias", label="N")

    dot.node("related_to", "related_to", shape="diamond")
    dot.edge("Person", "related_to", label="0..N")
    dot.edge("related_to", "Person", label="0..N")

    dot.node("relation_type", "relation_type", shape="ellipse")
    dot.edge("related_to", "relation_type")

    dot.node(
        "note_gender",
        "gender in {male, female, unknown}",
        shape="note"
    )
    dot.node(
        "note_rel",
        "relation_type in {father, mother, parent,\nspouse, son, daughter, child,\nbrother, sister, sibling}",
        shape="note"
    )

    dot.edge("p_gender", "note_gender", style="dashed", arrowhead="none")
    dot.edge("relation_type", "note_rel", style="dashed", arrowhead="none")

    output = dot.render(filename, cleanup=True)
    print(f"Saved to: {output}")


if __name__ == "__main__":
    build_canonical_er()
    
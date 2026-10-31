"""Affiche le centre (x y) du premier élément d'interface dont le texte correspond à la regex."""
import re
import sys
import xml.etree.ElementTree as ET

pattern = re.compile(sys.argv[2], re.I)
for node in ET.parse(sys.argv[1]).iter("node"):
    label = node.get("text", "") or node.get("content-desc", "")
    if label and pattern.search(label):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break

/*
 * export-fo-x -- Weiterentwicklung von docx4j-export-fo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.docx4j.convert.out.fo.x;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.docx4j.XmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Repairs two structural problems in the generated XSL-FO that would otherwise make
 * anchored shapes disappear.
 *
 * <ol>
 * <li><b>Absolutely positioned containers nested in {@code fo:inline}.</b> The docx-to-FO
 *     transform wraps each {@code w:r} in an {@code fo:inline}, so a shape produced from a
 *     {@code w:pict} inside that run lands inside it. FOP <em>silently drops</em> an
 *     absolutely positioned {@code fo:block-container} in that position -- no warning, no
 *     error, just missing output. Lifting it to the nearest block-level ancestor is enough
 *     to make FOP lay it out; verified against FOP 2.11.</li>
 * <li><b>Empty containers.</b> {@code fo:block-container} requires at least one block
 *     child; a decoration-only shape would otherwise abort the render with a
 *     ValidationException.</li>
 * </ol>
 *
 * <p>Runs on the complete FO document, once, immediately before it reaches the renderer.
 */
public final class FoAbsolutePositionHoister {

	private static final Logger log = LoggerFactory.getLogger(FoAbsolutePositionHoister.class);

	private static final String XSL_FO = "http://www.w3.org/1999/XSL/Format";
	private static final String INLINE = "inline";
	private static final String BLOCK_CONTAINER = "block-container";

	private FoAbsolutePositionHoister() {
	}

	/**
	 * @param foDocument serialized XSL-FO
	 * @return the repaired FO, or the input unchanged when there is nothing to do
	 *         (or when it cannot be parsed -- never fail the conversion over a fixup)
	 */
	public static String process(String foDocument) {

		if (foDocument == null || !foDocument.contains("absolute-position")) {
			// Fast path: no anchored shapes in this document.
			return foDocument;
		}

		try {
			Document doc = XmlUtils.getNewDocumentBuilder()
					.parse(new InputSource(new StringReader(foDocument)));

			int hoisted = hoistOutOfInlines(doc);
			int filled = fillEmptyContainers(doc);

			if (hoisted == 0 && filled == 0) {
				return foDocument;
			}
			log.debug("FO fixup: hoisted {} absolutely positioned container(s), "
					+ "gave {} empty container(s) a block child", hoisted, filled);
			return serialize(doc);

		} catch (Exception e) {
			log.warn("Could not post-process the XSL-FO ({}); using it unchanged. "
					+ "Anchored shapes may be missing from the output.", e.toString());
			return foDocument;
		}
	}

	private static int hoistOutOfInlines(Document doc) {
		int count = 0;
		for (Element container : absolutelyPositionedContainers(doc)) {

			Node outermostInline = null;
			for (Node p = container.getParentNode(); isFo(p, INLINE); p = p.getParentNode()) {
				outermostInline = p;
			}
			if (outermostInline == null) {
				continue; // already at block level
			}

			Node blockLevelParent = outermostInline.getParentNode();
			if (blockLevelParent == null) {
				continue;
			}
			container.getParentNode().removeChild(container);
			// Insert straight after the inline it came from, keeping document order
			// (and with it the painting order for overlapping shapes).
			blockLevelParent.insertBefore(container, outermostInline.getNextSibling());
			count++;
		}
		return count;
	}

	private static int fillEmptyContainers(Document doc) {
		int count = 0;
		NodeList containers = doc.getElementsByTagNameNS(XSL_FO, BLOCK_CONTAINER);
		for (int i = 0; i < containers.getLength(); i++) {
			Element container = (Element) containers.item(i);
			if (!hasElementChild(container)) {
				container.appendChild(doc.createElementNS(XSL_FO, "block"));
				count++;
			}
		}
		return count;
	}

	private static List<Element> absolutelyPositionedContainers(Document doc) {
		List<Element> result = new ArrayList<>();
		NodeList containers = doc.getElementsByTagNameNS(XSL_FO, BLOCK_CONTAINER);
		// Snapshot first: the NodeList is live and we are about to move nodes around.
		for (int i = 0; i < containers.getLength(); i++) {
			Element e = (Element) containers.item(i);
			if (isAbsolutelyPositioned(e)) {
				result.add(e);
			}
		}
		return result;
	}

	private static boolean isAbsolutelyPositioned(Element e) {
		return isAbsoluteValue(e.getAttribute("absolute-position"))
				|| isAbsoluteValue(e.getAttribute("position"));
	}

	private static boolean isAbsoluteValue(String value) {
		return "fixed".equals(value) || "absolute".equals(value);
	}

	private static boolean hasElementChild(Element e) {
		for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n.getNodeType() == Node.ELEMENT_NODE) {
				return true;
			}
		}
		return false;
	}

	private static boolean isFo(Node node, String localName) {
		return node != null
				&& node.getNodeType() == Node.ELEMENT_NODE
				&& XSL_FO.equals(node.getNamespaceURI())
				&& localName.equals(node.getLocalName());
	}

	private static String serialize(Document doc) throws Exception {
		Transformer t = TransformerFactory.newInstance().newTransformer();
		t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		t.setOutputProperty(OutputKeys.INDENT, "no");
		StringWriter out = new StringWriter();
		t.transform(new DOMSource(doc), new StreamResult(out));
		return out.toString();
	}
}

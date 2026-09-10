/*
   Licensed to Plutext Pty Ltd under one or more contributor license agreements.

 *  This file is part of docx4j.

    docx4j is licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.

    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */
/*
 * MODIFIED by the export-fo-x project, 2026.
 *
 * Taken from docx4j-export-fo 11.5.9 (Copyright Plutext Pty Ltd, Apache
 * License 2.0) and changed: resolves the whole mso-position-* family into page
 * coordinates; the debug border around every text box is gone.
 *
 * See NOTICE and README.md at the root of this repository.
 */
package org.docx4j.convert.out.fo;

import java.util.Map;

import org.docx4j.XmlUtils;
import org.docx4j.convert.out.common.AbstractWmlConversionContext;
import org.docx4j.convert.out.fo.x.VmlAnchor;
import org.docx4j.convert.out.fo.x.VmlFoBuilder;
import org.docx4j.convert.out.fo.x.VmlStyle;
import org.docx4j.model.structure.PageDimensions;
import org.docx4j.vml.CTTextbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * For no wrap (ie in front or behind), we use an fo:block-container with
 * {@code absolute-position}, and z-index to specify under or over.
 *
 * <p>{@code absolute-position="absolute"} is relative to the containing reference area;
 * {@code "fixed"} is relative to the page. Which of the two applies is decided by
 * {@link VmlAnchor}, from the shape's {@code mso-position-*-relative} values -- a shape
 * anchored to the page (a letterhead's address column, logo or fold marks) must be fixed,
 * because laying it out inside {@code fo:region-before} would both misplace it and
 * inflate the header's extent.
 *
 * <p>docx4j only implemented the {@code mso-position-horizontal-relative:text} case and
 * logged a warning for every other value without setting any geometry at all, so a
 * page-anchored shape ended up with no width and no left offset.
 *
 * @author jharrop
 */
public abstract class FOPictWriterNoWrapImpl extends FOPictWriterAbstract {

	protected static Logger log = LoggerFactory.getLogger(FOPictWriterNoWrapImpl.class);

	public FOPictWriterNoWrapImpl() {
		super();
	}

	@Override
	public Node handleVTextBoxNoWrap(AbstractWmlConversionContext context,
			Node modelContent, Document doc,
			org.docx4j.vml.VmlShapeElements shape,
			Map<String, String> props) {

		PageDimensions page = pageDimensions(context);
		if (page == null) {
			return context.getMessageWriter().message(context,
					"No page dimensions available; cannot position this text box.");
		}

		VmlStyle style = styleOf(shape);
		VmlAnchor anchor = VmlAnchor.resolve(style, page, false);

		Element container = VmlFoBuilder.createContainer(doc, shape, style, anchor);

		CTTextbox textBox = findTextbox(shape);
		if (textBox != null) {
			VmlFoBuilder.applyTextboxInset(container, textBox.getInset(), anchor);
			if (fitShapeToText(textBox)) {
				// Word grows the box to its content; a fixed height would truncate it.
				container.removeAttribute("height");
			}
		}

		if (modelContent != null) {
			XmlUtils.treeCopy(modelContent.getChildNodes(), container);
		}
		if (!hasElementChild(container)) {
			container.appendChild(VmlFoBuilder.emptyBlock(doc));
		}

		if (log.isDebugEnabled()) {
			log.debug("{} -> {}", style, XmlUtils.w3CDomNodeToString(container));
		}
		return container;
	}

	private static boolean fitShapeToText(CTTextbox textBox) {
		VmlStyle boxStyle = VmlStyle.parse(textBox.getStyle());
		String fit = boxStyle.get("mso-fit-shape-to-text", "f");
		return "t".equals(fit) || "true".equals(fit);
	}

	private static boolean hasElementChild(Element e) {
		for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n.getNodeType() == Node.ELEMENT_NODE) {
				return true;
			}
		}
		return false;
	}
}

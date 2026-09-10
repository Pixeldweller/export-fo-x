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
 * License 2.0) and changed: handles v:line, shapes without a textbox and
 * shapes with no fill, and parses the VML @style attribute defensively.
 *
 * See NOTICE and README.md at the root of this repository.
 */
package org.docx4j.convert.out.fo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.transform.TransformerException;

import org.docx4j.XmlUtils;
import org.docx4j.convert.out.common.AbstractWmlConversionContext;
import org.docx4j.convert.out.common.writer.AbstractPictWriter;
import org.docx4j.convert.out.fo.x.VmlAnchor;
import org.docx4j.convert.out.fo.x.VmlFoBuilder;
import org.docx4j.convert.out.fo.x.VmlStyle;
import org.docx4j.model.structure.PageDimensions;
import org.docx4j.vml.CTLine;
import org.docx4j.vml.CTShapetype;
import org.docx4j.vml.CTTextbox;
import org.docx4j.vml.VmlAllCoreAttributes;
import org.docx4j.vml.VmlShapeElements;
import org.docx4j.vml.wordprocessingDrawing.STVerticalAnchor;
import org.docx4j.vml.wordprocessingDrawing.STWrapType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Converts a {@code w:pict} -- the VML half of a Word drawing -- into XSL-FO.
 *
 * <p>docx4j only ever handled {@code v:shape}/{@code v:rect} <em>with a textbox</em> here,
 * and anything else was reported as "NOT IMPLEMENTED: support for w:pict; without
 * v:imagedata" and dropped. A typical letterhead is made almost entirely of the shapes
 * that fell into that gap: fold marks are {@code v:line}, rules and tint blocks are
 * {@code v:rect} with no text. export-fo-x renders those too.
 *
 * <p>Microsoft Word supports 5 "wrapping styles" for a text box:
 *
 * <ol>
 * <li>"in line with text" (still a TODO here)</li>
 * <li>square, and</li>
 * <li>tight -- handled with fo:float</li>
 * <li>behind, and</li>
 * <li>in front -- handled with an absolutely positioned block-container</li>
 * </ol>
 *
 * (There are some additional wrapping styles for images, eg top and bottom, which
 * is straightforward, and through, as to which see
 * https://wordribbon.tips.net/T009382_Understanding_Through_Text_Wrapping.html)
 *
 * @author jharrop
 */
public abstract class FOPictWriterAbstract extends AbstractPictWriter {

	protected static Logger log = LoggerFactory.getLogger(FOPictWriterAbstract.class);

	protected static String XSL_FO = VmlFoBuilder.XSL_FO;

	public FOPictWriterAbstract() {
		super();
	}

	@Override
	public Node toNode(AbstractWmlConversionContext context, Object unmarshalledNode,
			Node modelContent, TransformState state, Document doc)
			throws TransformerException {

		org.docx4j.wml.Pict pict = (org.docx4j.wml.Pict) unmarshalledNode;

		/*
            <w:pict>
              <v:shapetype id="_x0000_t202" coordsize="21600,21600" o:spt="202" path="m,l,21600r21600,l21600,xe">
                <v:stroke joinstyle="miter"/>
                <v:path gradientshapeok="t" o:connecttype="rect"/>
              </v:shapetype>
              <v:shape id="Text Box 2" o:spid="_x0000_s1026"
              			type="#_x0000_t202"
              			style="position:absolute;margin-left:0;margin-top:0;width:186.95pt;..."
              			o:gfxdata=" yuck!">
                <v:textbox style="mso-fit-shape-to-text:t">
                  <w:txbxContent>
                    <w:p><w:r><w:t>Content</w:t></w:r></w:p>
                  </w:txbxContent>
                </v:textbox>
              </v:shape>
            </w:pict>
		 */

		List<VmlShapeElements> shapes = findShapes(pict);
		if (shapes.isEmpty()) {
			return context.getMessageWriter().message(context,
					"Couldn't find v:shape (or v:rectangle etc) in w:pict.");
		}

		PageDimensions page = pageDimensions(context);
		if (page == null) {
			return context.getMessageWriter().message(context,
					"No page dimensions available; cannot position the shapes in this w:pict.");
		}

		// A w:pict normally holds one shape, but a v:shapetype plus several siblings is
		// legal, so convert each and hand back the lot.
		DocumentFragment fragment = doc.createDocumentFragment();
		Node textboxContent = modelContent;
		for (VmlShapeElements shape : shapes) {
			Node converted = convertShape(context, shape, textboxContent, doc, page);
			if (converted != null) {
				fragment.appendChild(converted);
				// The XSLT hands us the content of every textbox in this w:pict as one
				// blob, so it can only be given to the first shape that wants it.
				if (findTextbox(shape) != null) {
					textboxContent = null;
				}
			}
		}
		if (!fragment.hasChildNodes()) {
			return null;
		}
		return fragment;
	}

	private Node convertShape(AbstractWmlConversionContext context, VmlShapeElements shape,
			Node modelContent, Document doc, PageDimensions page) {

		VmlStyle style = styleOf(shape);
		CTTextbox textBox = findTextbox(shape);

		if (shape instanceof CTLine) {
			return convertLine(doc, (CTLine) shape, style, page);
		}
		if (textBox == null) {
			return convertDecoration(doc, shape, style, page);
		}
		return convertTextBox(context, shape, style, modelContent, doc);
	}

	/**
	 * A shape with no text: a rule, a frame, a tint block. docx4j discarded these
	 * outright, which is what removes the ruled boxes from a letterhead.
	 */
	private Node convertDecoration(Document doc, VmlShapeElements shape, VmlStyle style,
			PageDimensions page) {

		VmlAnchor anchor = VmlAnchor.resolve(style, page, false);
		if (anchor.getWidthPt() == null && anchor.getHeightPt() == null) {
			log.debug("Shape has neither width nor height; nothing to draw");
			return null;
		}
		Element container = VmlFoBuilder.createContainer(doc, shape, style, anchor);
		if (!container.hasAttribute("background-color") && !container.hasAttribute("border-style")) {
			// Neither filled nor stroked: an invisible shape, so don't emit anything.
			return null;
		}
		container.appendChild(VmlFoBuilder.emptyBlock(doc));
		return container;
	}

	/** A {@code v:line}: geometry comes from @from/@to rather than width/height. */
	private Node convertLine(Document doc, CTLine line, VmlStyle style, PageDimensions page) {

		double[] from = parsePoint(line.getFrom());
		double[] to = parsePoint(line.getTo());
		if (from == null || to == null) {
			log.debug("v:line without usable @from/@to; skipping");
			return null;
		}

		// @from/@to are stated in the same coordinate space the style's margin-left /
		// margin-top would use, so resolve a zero-sized anchor and translate by it.
		VmlAnchor origin = VmlAnchor.resolve(style, page, false);
		double dx = origin.getLeftPt();
		double dy = origin.getTopPt();

		VmlAnchor anchor = VmlAnchor.ofBox(dx, dy, 0, 0, origin.isPageFixed(), origin.getZIndex());
		return VmlFoBuilder.createLine(doc, line, style,
				from[0] + dx, from[1] + dy, to[0] + dx, to[1] + dy, anchor);
	}

	private Node convertTextBox(AbstractWmlConversionContext context, VmlShapeElements shape,
			VmlStyle style, Node modelContent, Document doc) {

		Map<String, String> props = style.asMap();

		STWrapType wrapType = wrapType(shape);
		if (wrapType != null) {
			if (STWrapType.SQUARE.equals(wrapType)
					|| STWrapType.TIGHT.equals(wrapType)
					|| STWrapType.THROUGH.equals(wrapType)) {
				// use fo:float
				return handleVTextBoxWrapped(context, modelContent, doc, shape, props);
			}
			if (STWrapType.TOP_AND_BOTTOM.equals(wrapType)) {
				// TODO, just as part of normal block flow
				log.warn("TODO: Add support for STWrapType.TOP_AND_BOTTOM");
			} else if (STWrapType.NONE.equals(wrapType)) {
				return handleVTextBoxNoWrap(context, modelContent, doc, shape, props);
			}
		}

		if (anchoredToPageTop(shape)) {
			return handleVTextBoxNoWrap(context, modelContent, doc, shape, props);
		}

		if ("absolute".equals(style.get("position", null))) {
			return handleVTextBoxNoWrap(context, modelContent, doc, shape, props);
		}

		if ("char".equals(style.get("mso-position-horizontal-relative", null))) {
			// in line with text
			log.warn("TODO: No support for mso-position-horizontal-relative:char");
		}

		return handleVTextBoxWrapped(context, modelContent, doc, shape, props);
	}

	/** All shapes directly inside the w:pict; the shapetype is a template, not a shape. */
	private static List<VmlShapeElements> findShapes(org.docx4j.wml.Pict pict) {
		List<VmlShapeElements> shapes = new ArrayList<>();
		for (Object o : pict.getAnyAndAny()) {
			o = XmlUtils.unwrap(o);
			if (o instanceof VmlShapeElements && !(o instanceof CTShapetype)) {
				if (o instanceof org.docx4j.vml.CTGroup) {
					log.warn("v:group is not supported; its shapes will not appear in the PDF. "
							+ "Ungrouping them in Word is the workaround.");
					continue;
				}
				shapes.add((VmlShapeElements) o);
			}
		}
		return shapes;
	}

	protected static CTTextbox findTextbox(VmlShapeElements shape) {
		for (Object o : shape.getEGShapeElements()) {
			o = XmlUtils.unwrap(o);
			if (o instanceof CTTextbox) {
				return (CTTextbox) o;
			}
		}
		return null;
	}

	private static org.docx4j.vml.wordprocessingDrawing.CTWrap findWrap(VmlShapeElements shape) {
		for (Object o : shape.getEGShapeElements()) {
			o = XmlUtils.unwrap(o);
			if (o instanceof org.docx4j.vml.wordprocessingDrawing.CTWrap) {
				return (org.docx4j.vml.wordprocessingDrawing.CTWrap) o;
			}
		}
		return null;
	}

	private static STWrapType wrapType(VmlShapeElements shape) {
		org.docx4j.vml.wordprocessingDrawing.CTWrap wrap = findWrap(shape);
		return wrap == null ? null : wrap.getType();
	}

	private static boolean anchoredToPageTop(VmlShapeElements shape) {
		org.docx4j.vml.wordprocessingDrawing.CTWrap wrap = findWrap(shape);
		return wrap != null && STVerticalAnchor.PAGE.equals(wrap.getAnchory());
	}

	protected static VmlStyle styleOf(VmlShapeElements shape) {
		if (shape instanceof VmlAllCoreAttributes) {
			return VmlStyle.parse(((VmlAllCoreAttributes) shape).getStyle());
		}
		log.warn("{} does not implement VmlAllCoreAttributes, so @style is unreadable",
				shape.getClass().getName());
		return VmlStyle.parse(null);
	}

	protected static PageDimensions pageDimensions(AbstractWmlConversionContext context) {
		try {
			return context.getSections().getCurrentSection().getPageDimensions();
		} catch (RuntimeException e) {
			log.warn("Could not determine the current section's page dimensions: {}", e.toString());
			return null;
		}
	}

	/** Parses a VML coordinate pair such as {@code "-4.9pt,297.2pt"} into points. */
	protected static double[] parsePoint(String pair) {
		if (pair == null) {
			return null;
		}
		String[] parts = pair.split(",");
		if (parts.length != 2) {
			return null;
		}
		Double x = VmlStyle.toPoints(parts[0]);
		Double y = VmlStyle.toPoints(parts[1]);
		return (x == null || y == null) ? null : new double[] {x, y};
	}

	abstract public Node handleVTextBoxNoWrap(AbstractWmlConversionContext context,
			Node modelContent, Document doc,
			org.docx4j.vml.VmlShapeElements shape,
			Map<String, String> props);

	abstract public Node handleVTextBoxWrapped(AbstractWmlConversionContext context,
			Node modelContent, Document doc,
			org.docx4j.vml.VmlShapeElements shape,
			Map<String, String> props);

	/**
	 * Draws the shape's own frame on {@code target}, if it declares one. Replaces the
	 * unconditional debug border docx4j used to paint around every text box.
	 */
	protected void applyShapeStroke(Element target, VmlShapeElements shape) {
		VmlFoBuilder.applyStroke(target, shape);
	}

	/**
	 * @deprecated docx4j drew a solid frame around every text box so that they were
	 *             visible during development. Word only draws one when the shape says
	 *             {@code stroked="t"}, so this is no longer called; see
	 *             {@link VmlFoBuilder}.
	 */
	@Deprecated
	protected void setBorders(Element ret) {
		ret.setAttribute("border-left-style", "solid");
		ret.setAttribute("border-top-style", "solid");
		ret.setAttribute("border-bottom-style", "solid");
		ret.setAttribute("border-right-style", "solid");
	}

	protected float parsePtsVal(String pts) {

		Double value = VmlStyle.toPoints(pts);
		if (value == null) {
			log.warn("No val! {}", pts);
			return -99; // preserved for callers that test for it
		}
		return value.floatValue();
	}
}

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

import java.util.Locale;
import java.util.Map;

import org.docx4j.vml.STTrueFalse;
import org.docx4j.vml.VmlAllShapeAttributes;
import org.docx4j.vml.VmlShapeElements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Turns a positioned VML shape into the XSL-FO that represents it.
 *
 * <p>Everything produced here is an {@code fo:block-container}. When the anchor could be
 * resolved against the page it carries {@code absolute-position="fixed"}, which FOP
 * positions from the page's top-left corner -- exactly the semantics Word gives a shape
 * anchored to the page.
 *
 * <p>Note that FOP silently discards an absolutely positioned {@code fo:block-container}
 * that sits inside an {@code fo:inline}; {@link FoAbsolutePositionHoister} lifts them out
 * afterwards, which is why nothing here worries about its insertion point.
 */
public final class VmlFoBuilder {

	private static final Logger log = LoggerFactory.getLogger(VmlFoBuilder.class);

	public static final String XSL_FO = "http://www.w3.org/1999/XSL/Format";
	public static final String SVG = "http://www.w3.org/2000/svg";

	/** Word's default v:textbox inset: left, top, right, bottom. */
	private static final double[] DEFAULT_INSET_PT = {7.2d, 3.6d, 7.2d, 3.6d};

	/** Below this the two endpoints of a line count as sharing an axis. */
	private static final double AXIS_TOLERANCE_PT = 0.75d;

	private static final Map<String, String> NAMED_COLOURS = Map.ofEntries(
			Map.entry("black", "#000000"), Map.entry("white", "#ffffff"),
			Map.entry("red", "#ff0000"), Map.entry("green", "#008000"),
			Map.entry("blue", "#0000ff"), Map.entry("yellow", "#ffff00"),
			Map.entry("gray", "#808080"), Map.entry("grey", "#808080"),
			Map.entry("silver", "#c0c0c0"), Map.entry("navy", "#000080"),
			Map.entry("teal", "#008080"), Map.entry("olive", "#808000"),
			Map.entry("purple", "#800080"), Map.entry("maroon", "#800000"),
			Map.entry("lime", "#00ff00"), Map.entry("aqua", "#00ffff"),
			Map.entry("fuchsia", "#ff00ff"),
			Map.entry("windowtext", "#000000"), Map.entry("window", "#ffffff"));

	private VmlFoBuilder() {
	}

	/**
	 * Creates the {@code fo:block-container} for a shape, positioned and styled but empty.
	 * Callers add the shape's content (converted textbox paragraphs, an image, ...).
	 */
	public static Element createContainer(Document doc, VmlShapeElements shape,
			VmlStyle style, VmlAnchor anchor) {

		Element container = doc.createElementNS(XSL_FO, "block-container");

		applyPosition(container, anchor);
		applyFill(container, shape);
		applyStroke(container, shape);
		applyTextAnchor(container, style);

		if (anchor.getZIndex() != null) {
			container.setAttribute("z-index", anchor.getZIndex().toString());
		}
		// Word clips a text box to its frame. We deliberately do not: FOP's line breaking
		// is not Word's, so a box sized to the last millimetre in Word can come out a line
		// taller here, and losing that line is worse than letting it spill.
		container.setAttribute("overflow", "visible");

		return container;
	}

	/**
	 * Applies the v:textbox {@code @inset} (or Word's default) by shrinking the container
	 * onto its text area, rather than by emitting padding.
	 *
	 * <p>FOP does not shift the content of an absolutely positioned block-container by its
	 * padding -- the text lands on the container's own edge -- so a logo in a text box came
	 * out its inset too far left and up. Folding the inset into left/top/width/height gives
	 * Word's geometry exactly and does not depend on how padding is treated.
	 */
	public static void applyTextboxInset(Element container, String inset, VmlAnchor anchor) {

		double[] pt = parseInset(inset);

		container.setAttribute("left", format(anchor.getLeftPt() + pt[0]));
		container.setAttribute("top", format(anchor.getTopPt() + pt[1]));

		shrink(container, "width", anchor.getWidthPt(), pt[0] + pt[2]);
		shrink(container, "height", anchor.getHeightPt(), pt[1] + pt[3]);
	}

	/** Reduces a dimension by the inset, dropping it if nothing would be left. */
	private static void shrink(Element container, String attribute, Double size, double by) {
		if (size == null) {
			return;
		}
		double remaining = size - by;
		if (remaining <= 0) {
			log.debug("v:textbox inset leaves no {}; letting the content size the box", attribute);
			container.removeAttribute(attribute);
			return;
		}
		container.setAttribute(attribute, format(remaining));
	}

	/**
	 * Parses a v:textbox inset ("0,0,0,0", "4.25pt,4.25pt,4.25pt,4.25pt", ...) into
	 * left/top/right/bottom points. Missing entries fall back to Word's defaults.
	 */
	static double[] parseInset(String inset) {
		double[] result = DEFAULT_INSET_PT.clone();
		if (inset == null || inset.isBlank()) {
			return result;
		}
		String[] parts = inset.split(",");
		for (int i = 0; i < result.length && i < parts.length; i++) {
			String part = parts[i].trim();
			if (part.isEmpty()) {
				continue; // an omitted entry means "keep the default"
			}
			Double v = VmlStyle.toPoints(part);
			if (v != null) {
				result[i] = v;
			}
		}
		return result;
	}

	private static void applyPosition(Element container, VmlAnchor anchor) {
		if (anchor.isPageFixed()) {
			container.setAttribute("absolute-position", "fixed");
			container.setAttribute("top", format(anchor.getTopPt()));
		} else {
			// Vertically flow-relative: keep it in the flow and only offset it.
			container.setAttribute("absolute-position", "absolute");
			container.setAttribute("top", format(anchor.getTopPt()));
		}
		container.setAttribute("left", format(anchor.getLeftPt()));

		if (anchor.getWidthPt() != null) {
			container.setAttribute("width", format(anchor.getWidthPt()));
		}
		if (anchor.getHeightPt() != null) {
			container.setAttribute("height", format(anchor.getHeightPt()));
		}
	}

	private static void applyFill(Element container, VmlShapeElements shape) {
		if (!(shape instanceof VmlAllShapeAttributes)) {
			return;
		}
		String fillColour = colour(getFillcolor(shape));
		boolean filled = !isFalse(getFilled(shape));
		if (filled && fillColour != null) {
			container.setAttribute("background-color", fillColour);
		}
	}

	/**
	 * Draws the shape's frame -- and only when it has one. VML says {@code stroked="f"}
	 * for the many text boxes that exist purely to place text, which is most of a
	 * letterhead.
	 */
	public static void applyStroke(Element container, VmlShapeElements shape) {
		if (!(shape instanceof VmlAllShapeAttributes)) {
			return;
		}
		VmlAllShapeAttributes attrs = (VmlAllShapeAttributes) shape;
		if (isFalse(attrs.getStroked())) {
			return; // stroked="f" -- Word draws no frame, and neither should we
		}
		String strokeColour = colour(attrs.getStrokecolor());
		Double weight = VmlStyle.toPoints(attrs.getStrokeweight());
		if (strokeColour == null && weight == null) {
			return; // no explicit stroke: don't invent a frame
		}
		container.setAttribute("border-style", "solid");
		container.setAttribute("border-color", strokeColour == null ? "#000000" : strokeColour);
		container.setAttribute("border-width", format(weight == null ? 0.75d : weight));
	}

	private static void applyTextAnchor(Element container, VmlStyle style) {
		String anchor = style.get("v-text-anchor", "top");
		switch (anchor) {
			case "middle":
			case "middle-center":
				container.setAttribute("display-align", "center");
				break;
			case "bottom":
			case "bottom-center":
				container.setAttribute("display-align", "after");
				break;
			default:
				container.setAttribute("display-align", "before");
				break;
		}
	}

	/**
	 * Renders a {@code v:line}. Horizontal and vertical lines become a one-sided border,
	 * which stays crisp at any zoom; a genuinely diagonal line becomes a small inline SVG,
	 * since XSL-FO has no way to draw one directly.
	 */
	public static Element createLine(Document doc, VmlShapeElements shape, VmlStyle style,
			double x1, double y1, double x2, double y2, VmlAnchor anchor) {

		double left = Math.min(x1, x2);
		double top = Math.min(y1, y2);
		double width = Math.abs(x2 - x1);
		double height = Math.abs(y2 - y1);

		String strokeColour = colour(shape instanceof VmlAllShapeAttributes
				? ((VmlAllShapeAttributes) shape).getStrokecolor() : null);
		if (strokeColour == null) {
			strokeColour = "#000000";
		}
		Double weight = VmlStyle.toPoints(shape instanceof VmlAllShapeAttributes
				? ((VmlAllShapeAttributes) shape).getStrokeweight() : null);
		double strokeWidth = weight == null ? 0.75d : weight;

		boolean horizontal = height <= AXIS_TOLERANCE_PT;
		boolean vertical = width <= AXIS_TOLERANCE_PT;

		Element container = doc.createElementNS(XSL_FO, "block-container");
		container.setAttribute("absolute-position", anchor.isPageFixed() ? "fixed" : "absolute");
		// Word centres the stroke on the line's own path, while FOP draws a container's
		// border just outside the offset it is given, so nudge the box by half the stroke
		// to put the ink where Word puts it.
		container.setAttribute("left", format(vertical ? left + strokeWidth / 2 : left));
		container.setAttribute("top", format(horizontal ? top + strokeWidth / 2 : top));
		if (anchor.getZIndex() != null) {
			container.setAttribute("z-index", anchor.getZIndex().toString());
		}

		if (horizontal || vertical) {
			container.setAttribute("width", format(horizontal ? width : strokeWidth));
			container.setAttribute("height", format(vertical ? height : strokeWidth));
			container.setAttribute(horizontal ? "border-top-style" : "border-left-style", "solid");
			container.setAttribute(horizontal ? "border-top-color" : "border-left-color", strokeColour);
			container.setAttribute(horizontal ? "border-top-width" : "border-left-width", format(strokeWidth));
			// fo:block-container requires at least one block child.
			container.appendChild(emptyBlock(doc));
		} else {
			container.setAttribute("width", format(width));
			container.setAttribute("height", format(height));
			container.appendChild(svgLine(doc, width, height,
					x1 - left, y1 - top, x2 - left, y2 - top, strokeColour, strokeWidth));
		}
		return container;
	}

	private static Element svgLine(Document doc, double width, double height,
			double x1, double y1, double x2, double y2, String colour, double strokeWidth) {

		Element block = doc.createElementNS(XSL_FO, "block");
		Element ifo = doc.createElementNS(XSL_FO, "instream-foreign-object");
		ifo.setAttribute("content-width", format(width));
		ifo.setAttribute("content-height", format(height));

		Element svg = doc.createElementNS(SVG, "svg");
		svg.setAttribute("width", format(width));
		svg.setAttribute("height", format(height));
		svg.setAttribute("viewBox", "0 0 " + round(width) + " " + round(height));

		Element line = doc.createElementNS(SVG, "line");
		line.setAttribute("x1", round(x1));
		line.setAttribute("y1", round(y1));
		line.setAttribute("x2", round(x2));
		line.setAttribute("y2", round(y2));
		line.setAttribute("stroke", colour);
		line.setAttribute("stroke-width", round(strokeWidth));

		svg.appendChild(line);
		ifo.appendChild(svg);
		block.appendChild(ifo);
		return block;
	}

	/** An empty {@code fo:block}, so a decoration-only container satisfies FOP's content model. */
	public static Element emptyBlock(Document doc) {
		return doc.createElementNS(XSL_FO, "block");
	}

	/** Normalises a VML colour to something FO understands, or null if unusable. */
	static String colour(String vmlColour) {
		if (vmlColour == null || vmlColour.isBlank()) {
			return null;
		}
		String value = vmlColour.trim().toLowerCase(Locale.ROOT);
		// Word may append a theme hint, eg "#4472c4 [3204]"
		int bracket = value.indexOf('[');
		if (bracket > 0) {
			value = value.substring(0, bracket).trim();
		}
		String named = NAMED_COLOURS.get(value);
		if (named != null) {
			return named;
		}
		if (value.matches("#[0-9a-f]{6}") || value.matches("#[0-9a-f]{3}")) {
			return value;
		}
		if (value.matches("[0-9a-f]{6}")) {
			return "#" + value;
		}
		log.debug("Unrecognised VML colour '{}'", vmlColour);
		return null;
	}

	private static boolean isFalse(STTrueFalse value) {
		if (value == null) {
			return false;
		}
		String v = value.value();
		return "f".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) || "0".equals(v);
	}

	private static STTrueFalse getFilled(VmlShapeElements shape) {
		try {
			return (STTrueFalse) shape.getClass().getMethod("getFilled").invoke(shape);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null; // not every shape type carries @filled
		}
	}

	private static String getFillcolor(VmlShapeElements shape) {
		try {
			return (String) shape.getClass().getMethod("getFillcolor").invoke(shape);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}

	/** Formats a point value for an FO attribute. */
	public static String format(double pts) {
		return round(pts) + "pt";
	}

	private static String round(double value) {
		return String.valueOf(Math.round(value * 1000d) / 1000d);
	}
}

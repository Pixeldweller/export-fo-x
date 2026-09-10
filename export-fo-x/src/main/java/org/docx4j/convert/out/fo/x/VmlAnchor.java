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

import org.docx4j.model.structure.PageDimensions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the VML {@code mso-position-*} family into coordinates measured from the
 * top-left corner of the page, so that a shape can be emitted as an
 * {@code fo:block-container} with {@code absolute-position="fixed"}.
 *
 * <p>This is what makes a Word "Kopfbogen" (letterhead) survive the conversion: its
 * address column, logo and fold marks are anchored to the <em>page</em>, not to the
 * header's text flow, and must not be laid out inside {@code fo:region-before}.
 *
 * <p>Horizontal anchoring always resolves, because every VML horizontal origin
 * (page / margin / text / column / char / *-margin-area) maps onto a known x on the page.
 * Vertical anchoring resolves for the page and margin-area origins; for
 * {@code text}/{@code line}/{@code paragraph} the origin depends on where the anchor
 * paragraph happens to land, which is unknown before layout, so {@link #isPageFixed()}
 * is false and the caller should fall back to in-flow positioning.
 */
public final class VmlAnchor {

	private static final Logger log = LoggerFactory.getLogger(VmlAnchor.class);

	private final double leftPt;
	private final double topPt;
	private final Double widthPt;
	private final Double heightPt;
	private final boolean pageFixed;
	private final Integer zIndex;

	private VmlAnchor(double leftPt, double topPt, Double widthPt, Double heightPt,
			boolean pageFixed, Integer zIndex) {
		this.leftPt = leftPt;
		this.topPt = topPt;
		this.widthPt = widthPt;
		this.heightPt = heightPt;
		this.pageFixed = pageFixed;
		this.zIndex = zIndex;
	}

	/** Distance from the left page edge, in points. */
	public double getLeftPt() {
		return leftPt;
	}

	/** Distance from the top page edge, in points. Only meaningful if {@link #isPageFixed()}. */
	public double getTopPt() {
		return topPt;
	}

	/** Shape width in points, or null when the style gives none (eg v:line). */
	public Double getWidthPt() {
		return widthPt;
	}

	/** Shape height in points, or null when the style gives none. */
	public Double getHeightPt() {
		return heightPt;
	}

	/** True when both axes could be resolved against the page. */
	public boolean isPageFixed() {
		return pageFixed;
	}

	public Integer getZIndex() {
		return zIndex;
	}

	/**
	 * @param style     the shape's parsed {@code @style}
	 * @param page      dimensions of the section the shape belongs to
	 * @param evenPage  true when resolving for an even page, which flips
	 *                  inner/outer anchoring
	 */
	public static VmlAnchor resolve(VmlStyle style, PageDimensions page, boolean evenPage) {

		Geometry geo = Geometry.of(page);

		Double width = style.lengthPt("width");
		Double height = style.lengthPt("height");

		double left = resolveHorizontal(style, geo, width, evenPage);

		String verticalRelative = style.get("mso-position-vertical-relative", "text");
		boolean verticalIsPageFixed = isVerticalOriginKnown(verticalRelative);
		double top = verticalIsPageFixed
				? resolveVertical(style, geo, height, verticalRelative, evenPage)
				: style.lengthPt("margin-top", 0d);

		if (!verticalIsPageFixed && log.isDebugEnabled()) {
			log.debug("mso-position-vertical-relative:{} is flow-dependent; "
					+ "keeping the shape in the flow", verticalRelative);
		}

		return new VmlAnchor(left, top, width, height, verticalIsPageFixed, style.intValue("z-index"));
	}

	/**
	 * Builds an anchor straight from an explicit page-coordinate box, used for shapes
	 * whose geometry comes from attributes rather than from {@code width}/{@code height}
	 * (v:line's {@code from}/{@code to}).
	 */
	public static VmlAnchor ofBox(double leftPt, double topPt, double widthPt, double heightPt,
			boolean pageFixed, Integer zIndex) {
		return new VmlAnchor(leftPt, topPt, widthPt, heightPt, pageFixed, zIndex);
	}

	private static double resolveHorizontal(VmlStyle style, Geometry geo, Double width, boolean evenPage) {

		String relative = style.get("mso-position-horizontal-relative", "text");
		double origin;
		double available;

		switch (relative) {
			case "page":
				origin = 0;
				available = geo.pageWidth;
				break;
			case "left-margin-area":
				origin = 0;
				available = geo.marginLeft;
				break;
			case "right-margin-area":
				origin = geo.pageWidth - geo.marginRight;
				available = geo.marginRight;
				break;
			case "inner-margin-area":
				origin = evenPage ? geo.pageWidth - geo.marginRight : 0;
				available = evenPage ? geo.marginRight : geo.marginLeft;
				break;
			case "outer-margin-area":
				origin = evenPage ? 0 : geo.pageWidth - geo.marginRight;
				available = evenPage ? geo.marginLeft : geo.marginRight;
				break;
			case "margin":
			case "text":
			case "column":
			case "char":
			default:
				// The text/column origin is the left edge of the writable area. In a header
				// or footer that is exactly the page's left margin, which is why a letterhead
				// anchored to "text" still lands deterministically.
				origin = geo.marginLeft;
				available = geo.writableWidth;
				break;
		}

		return origin + offsetWithin(
				style.get("mso-position-horizontal", "absolute"),
				style.lengthPt("margin-left", 0d),
				available, width, evenPage);
	}

	private static double resolveVertical(VmlStyle style, Geometry geo, Double height,
			String relative, boolean evenPage) {

		double origin;
		double available;

		switch (relative) {
			case "page":
				origin = 0;
				available = geo.pageHeight;
				break;
			case "top-margin-area":
				origin = 0;
				available = geo.marginTop;
				break;
			case "bottom-margin-area":
				origin = geo.pageHeight - geo.marginBottom;
				available = geo.marginBottom;
				break;
			case "inner-margin-area":
			case "outer-margin-area":
				// Vertically these behave like the top margin area in Word.
				origin = 0;
				available = geo.marginTop;
				break;
			case "margin":
			default:
				origin = geo.marginTop;
				available = geo.writableHeight;
				break;
		}

		return origin + offsetWithin(
				style.get("mso-position-vertical", "absolute"),
				style.lengthPt("margin-top", 0d),
				available, height, evenPage);
	}

	private static boolean isVerticalOriginKnown(String relative) {
		switch (relative) {
			case "page":
			case "margin":
			case "top-margin-area":
			case "bottom-margin-area":
			case "inner-margin-area":
			case "outer-margin-area":
				return true;
			default:
				// "text", "line", "paragraph" -- depends on the flow
				return false;
		}
	}

	/**
	 * Offset of the shape inside its anchor container, honouring the keyword alignments
	 * Word offers in addition to an absolute offset.
	 */
	private static double offsetWithin(String alignment, double absoluteOffset,
			double available, Double shapeSize, boolean evenPage) {

		String value = alignment == null ? "absolute" : alignment.toLowerCase(Locale.ROOT);
		double size = shapeSize == null ? 0d : shapeSize;

		switch (value) {
			case "left":
			case "top":
				return 0d;
			case "center":
				return (available - size) / 2d;
			case "right":
			case "bottom":
				return available - size;
			case "inside":
				return evenPage ? available - size : 0d;
			case "outside":
				return evenPage ? 0d : available - size;
			case "absolute":
			default:
				return absoluteOffset;
		}
	}

	/** Page geometry in points, derived once from the section's twip values. */
	private static final class Geometry {

		private static final double TWIPS_PER_POINT = 20d;

		final double pageWidth;
		final double pageHeight;
		final double marginLeft;
		final double marginRight;
		final double marginTop;
		final double marginBottom;
		final double writableWidth;
		final double writableHeight;

		private Geometry(PageDimensions page) {
			pageWidth = pt(page.getPgSz() == null || page.getPgSz().getW() == null
					? 11906 : page.getPgSz().getW().intValue());
			pageHeight = pt(page.getPgSz() == null || page.getPgSz().getH() == null
					? 16838 : page.getPgSz().getH().intValue());
			marginLeft = margin(page, Side.LEFT);
			marginRight = margin(page, Side.RIGHT);
			marginTop = margin(page, Side.TOP);
			marginBottom = margin(page, Side.BOTTOM);
			writableWidth = pt(page.getWritableWidthTwips());
			writableHeight = pt(page.getWritableHeightTwips());
		}

		static Geometry of(PageDimensions page) {
			return new Geometry(page);
		}

		private enum Side { LEFT, RIGHT, TOP, BOTTOM }

		private static double margin(PageDimensions page, Side side) {
			if (page.getPgMar() == null) {
				return 0d;
			}
			java.math.BigInteger v;
			switch (side) {
				case LEFT: v = page.getPgMar().getLeft(); break;
				case RIGHT: v = page.getPgMar().getRight(); break;
				case TOP: v = page.getPgMar().getTop(); break;
				default: v = page.getPgMar().getBottom(); break;
			}
			return v == null ? 0d : pt(v.intValue());
		}

		private static double pt(int twips) {
			return twips / TWIPS_PER_POINT;
		}
	}
}

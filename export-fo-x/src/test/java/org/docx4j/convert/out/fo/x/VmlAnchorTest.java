package org.docx4j.convert.out.fo.x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.docx4j.model.structure.PageDimensions;
import org.docx4j.wml.SectPr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coordinates here are the ones Microsoft Word itself produced for the sample letterhead,
 * read back out of its PDF, so these assertions pin the conversion to Word's own geometry.
 */
class VmlAnchorTest {

	/** A4 with the sample's margins: left 1361, right 2834, top 2948, bottom 1049 twips. */
	private PageDimensions page;

	@BeforeEach
	void setUp() {
		SectPr.PgSz size = new SectPr.PgSz();
		size.setW(BigInteger.valueOf(11906));
		size.setH(BigInteger.valueOf(16838));

		SectPr.PgMar margins = new SectPr.PgMar();
		margins.setLeft(BigInteger.valueOf(1361));
		margins.setRight(BigInteger.valueOf(2834));
		margins.setTop(BigInteger.valueOf(2948));
		margins.setBottom(BigInteger.valueOf(1049));

		page = new PageDimensions(size, margins);
	}

	@Test
	void pageAnchoredColumnKeepsWordsCoordinates() {
		// "Text Box 7": the address column down the right-hand margin.
		VmlAnchor anchor = VmlAnchor.resolve(VmlStyle.parse(
				"position:absolute;margin-left:467.75pt;margin-top:147.35pt;width:113.4pt;"
				+ "height:644.8pt;mso-position-horizontal:absolute;"
				+ "mso-position-horizontal-relative:page;mso-position-vertical:absolute;"
				+ "mso-position-vertical-relative:page"), page, false);

		assertTrue(anchor.isPageFixed());
		assertEquals(467.75, anchor.getLeftPt(), 0.01);
		assertEquals(147.35, anchor.getTopPt(), 0.01);
		assertEquals(113.4, anchor.getWidthPt(), 0.01);
	}

	@Test
	void textRelativeHorizontalStartsAtTheLeftPageMargin() {
		// "Text Box 10": the letterhead title. Word placed it at 283.55pt, which is the
		// 24mm left margin plus the shape's own 215.5pt offset.
		VmlAnchor anchor = VmlAnchor.resolve(VmlStyle.parse(
				"position:absolute;margin-left:215.5pt;margin-top:43.6pt;width:153.85pt;"
				+ "height:45.25pt;mso-position-horizontal:absolute;"
				+ "mso-position-horizontal-relative:text;mso-position-vertical:absolute;"
				+ "mso-position-vertical-relative:page"), page, false);

		assertTrue(anchor.isPageFixed());
		assertEquals(68.05 + 215.5, anchor.getLeftPt(), 0.01);
		assertEquals(43.6, anchor.getTopPt(), 0.01);
	}

	@Test
	void flowRelativeVerticalIsNotPageFixed() {
		// Anchored to the text, so where it lands depends on layout: it has to stay in
		// the flow rather than be nailed to the page.
		VmlAnchor anchor = VmlAnchor.resolve(VmlStyle.parse(
				"position:absolute;margin-left:10pt;margin-top:20pt;width:100pt;height:50pt;"
				+ "mso-position-vertical-relative:text"), page, false);

		assertFalse(anchor.isPageFixed());
		assertEquals(20.0, anchor.getTopPt(), 0.01);
	}

	@Test
	void centredShapeIsCentredInItsAnchorContainer() {
		VmlAnchor anchor = VmlAnchor.resolve(VmlStyle.parse(
				"position:absolute;width:100pt;height:50pt;mso-position-horizontal:center;"
				+ "mso-position-horizontal-relative:page;mso-position-vertical:top;"
				+ "mso-position-vertical-relative:page"), page, false);

		assertEquals((595.3 - 100) / 2, anchor.getLeftPt(), 0.05);
		assertEquals(0.0, anchor.getTopPt(), 0.01);
	}

	@Test
	void insideAndOutsideFlipOnEvenPages() {
		VmlStyle style = VmlStyle.parse(
				"position:absolute;width:100pt;mso-position-horizontal:inside;"
				+ "mso-position-horizontal-relative:page;mso-position-vertical:top;"
				+ "mso-position-vertical-relative:page");

		assertEquals(0.0, VmlAnchor.resolve(style, page, false).getLeftPt(), 0.01);
		assertEquals(595.3 - 100, VmlAnchor.resolve(style, page, true).getLeftPt(), 0.05);
	}

	@Test
	void rightMarginAreaStartsWhereTheTextEnds() {
		VmlAnchor anchor = VmlAnchor.resolve(VmlStyle.parse(
				"position:absolute;margin-left:0pt;width:50pt;"
				+ "mso-position-horizontal:absolute;"
				+ "mso-position-horizontal-relative:right-margin-area;"
				+ "mso-position-vertical:top;mso-position-vertical-relative:page"), page, false);

		assertEquals(595.3 - 141.7, anchor.getLeftPt(), 0.05);
	}
}

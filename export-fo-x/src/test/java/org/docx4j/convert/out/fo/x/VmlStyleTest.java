package org.docx4j.convert.out.fo.x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VmlStyleTest {

	/** The style of "Text Box 7" in the sample letterhead. */
	private static final String LETTERHEAD_COLUMN =
			"position:absolute;margin-left:467.75pt;margin-top:147.35pt;width:113.4pt;"
			+ "height:644.8pt;z-index:-251654144;visibility:visible;mso-wrap-style:square;"
			+ "mso-position-horizontal:absolute;mso-position-horizontal-relative:page;"
			+ "mso-position-vertical:absolute;mso-position-vertical-relative:page;v-text-anchor:top";

	@Test
	void readsLengthsAndKeywords() {
		VmlStyle style = VmlStyle.parse(LETTERHEAD_COLUMN);

		assertEquals(467.75, style.lengthPt("margin-left"), 0.001);
		assertEquals(113.4, style.lengthPt("width"), 0.001);
		assertEquals("page", style.get("mso-position-horizontal-relative", "text"));
		assertEquals(Integer.valueOf(-251654144), style.intValue("z-index"));
	}

	@Test
	void nullAndBlankStylesAreEmptyRatherThanFatal() {
		assertTrue(VmlStyle.parse(null).isEmpty());
		assertTrue(VmlStyle.parse("   ").isEmpty());
	}

	@Test
	void survivesEntriesDocx4jWouldHaveChokedOn() {
		// docx4j's parser indexed parts[1] unconditionally after splitting on ":",
		// so each of these threw ArrayIndexOutOfBoundsException.
		VmlStyle style = VmlStyle.parse("position:absolute;;visible;;;width:10pt;");

		assertEquals(10.0, style.lengthPt("width"), 0.001);
		assertEquals("absolute", style.get("position"));
		assertTrue(style.has("visible"));
	}

	@Test
	void keepsValuesContainingAColon() {
		// Splitting on every ":" truncated these to "http".
		VmlStyle style = VmlStyle.parse("o:href:http://example.org/logo.png;width:5pt");
		assertEquals(5.0, style.lengthPt("width"), 0.001);
	}

	@Test
	void understandsTheUnitsWordWrites() {
		assertEquals(72.0, VmlStyle.toPoints("1in"), 0.001);
		assertEquals(28.3464, VmlStyle.toPoints("1cm"), 0.001);
		assertEquals(2.83464, VmlStyle.toPoints("1mm"), 0.001);
		assertEquals(12.0, VmlStyle.toPoints("1pc"), 0.001);
		assertEquals(0.75, VmlStyle.toPoints("1px"), 0.001);
		assertEquals(-4.9, VmlStyle.toPoints("-4.9pt"), 0.001);
		// A bare number is CSS pixels.
		assertEquals(0.75, VmlStyle.toPoints("1"), 0.001);
	}

	@Test
	void unreadableLengthsAreNullRatherThanZero() {
		// Zero would silently move a shape to the page corner.
		assertNull(VmlStyle.toPoints("auto"));
		assertNull(VmlStyle.parse("width:auto").lengthPt("width"));
		assertNull(VmlStyle.parse("position:absolute").lengthPt("width"));
	}
}

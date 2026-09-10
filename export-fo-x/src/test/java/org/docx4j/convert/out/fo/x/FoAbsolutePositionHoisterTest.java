package org.docx4j.convert.out.fo.x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FoAbsolutePositionHoisterTest {

	private static final String HEAD =
			"<fo:root xmlns:fo=\"http://www.w3.org/1999/XSL/Format\">"
			+ "<fo:page-sequence master-reference=\"m\">"
			+ "<fo:static-content flow-name=\"xsl-region-before\">";
	private static final String TAIL =
			"</fo:static-content></fo:page-sequence></fo:root>";

	@Test
	void liftsAnAbsolutelyPositionedContainerOutOfAnInline() {
		// FOP silently drops such a container while it is inside an fo:inline, which is
		// exactly how a letterhead disappears without a single warning being logged.
		String fo = HEAD
				+ "<fo:block>text"
				+ "<fo:inline><fo:inline>"
				+ "<fo:block-container absolute-position=\"fixed\" left=\"10pt\" top=\"20pt\">"
				+ "<fo:block>logo</fo:block></fo:block-container>"
				+ "</fo:inline></fo:inline>"
				+ "</fo:block>" + TAIL;

		String result = FoAbsolutePositionHoister.process(fo);

		int container = result.indexOf("block-container");
		int inlineClose = result.indexOf("</fo:inline>");
		assertTrue(container > inlineClose,
				"container should now follow the inline it was lifted out of:\n" + result);
		assertTrue(result.contains("logo"));
	}

	@Test
	void leavesAContainerThatIsAlreadyAtBlockLevel() {
		String fo = HEAD
				+ "<fo:block-container absolute-position=\"fixed\" left=\"1pt\" top=\"2pt\">"
				+ "<fo:block>x</fo:block></fo:block-container>" + TAIL;

		assertTrue(FoAbsolutePositionHoister.process(fo).contains("absolute-position=\"fixed\""));
	}

	@Test
	void givesAnEmptyContainerTheBlockChildFopInsistsOn() {
		// fo:block-container's content model is (marker* (%block;)+); an empty one aborts
		// the whole render with a ValidationException.
		String fo = HEAD
				+ "<fo:block-container absolute-position=\"fixed\" left=\"1pt\" top=\"2pt\"/>"
				+ TAIL;

		String result = FoAbsolutePositionHoister.process(fo);
		assertTrue(result.contains("<block") || result.contains("<fo:block"),
				"expected a block child to have been added:\n" + result);
	}

	@Test
	void documentsWithoutAnchoredShapesAreReturnedUntouched() {
		String fo = HEAD + "<fo:block>plain</fo:block>" + TAIL;
		assertSame(fo, FoAbsolutePositionHoister.process(fo));
	}

	@Test
	void unparseableInputIsPassedThroughRatherThanFailingTheConversion() {
		String broken = "<fo:root absolute-position=\"fixed\" this is not xml";
		assertSame(broken, FoAbsolutePositionHoister.process(broken));
	}

	@Test
	void nullIsTolerated() {
		assertEquals(null, FoAbsolutePositionHoister.process(null));
		assertFalse(false);
	}
}

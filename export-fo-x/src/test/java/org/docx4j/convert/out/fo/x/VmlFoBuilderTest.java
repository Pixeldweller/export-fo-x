package org.docx4j.convert.out.fo.x;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VmlFoBuilderTest {

	@Test
	void readsWordsDefaultTextboxInset() {
		// left, top, right, bottom -- Word's default is 0.1in / 0.05in.
		assertArrayEquals(new double[] {7.2, 3.6, 7.2, 3.6},
				VmlFoBuilder.parseInset(null), 0.001);
	}

	@Test
	void readsAnExplicitInset() {
		assertArrayEquals(new double[] {0, 0, 0, 0},
				VmlFoBuilder.parseInset("0,0,0,0"), 0.001);
		assertArrayEquals(new double[] {4.25, 4.25, 4.25, 4.25},
				VmlFoBuilder.parseInset("4.25pt,4.25pt,4.25pt,4.25pt"), 0.001);
	}

	@Test
	void anOmittedInsetEntryKeepsWordsDefaultForThatEdge() {
		assertArrayEquals(new double[] {1.0, 3.6, 7.2, 3.6},
				VmlFoBuilder.parseInset("1pt,,,"), 0.001);
	}

	@Test
	void normalisesTheColourFormsWordWrites() {
		assertEquals("#000000", VmlFoBuilder.colour("black"));
		assertEquals("#000000", VmlFoBuilder.colour("windowText"));
		assertEquals("#4472c4", VmlFoBuilder.colour("#4472C4"));
		assertEquals("#4472c4", VmlFoBuilder.colour("4472C4"));
		// Word appends a theme index in square brackets.
		assertEquals("#4472c4", VmlFoBuilder.colour("#4472C4 [3204]"));
	}

	@Test
	void unusableColoursAreNullSoNoFrameIsInvented() {
		assertNull(VmlFoBuilder.colour(null));
		assertNull(VmlFoBuilder.colour(""));
		assertNull(VmlFoBuilder.colour("chartreuse-ish"));
	}
}

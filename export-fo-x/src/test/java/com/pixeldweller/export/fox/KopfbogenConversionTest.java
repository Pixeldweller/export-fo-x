package com.pixeldweller.export.fox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import java.awt.image.BufferedImage;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.docx4j.fonts.PhysicalFonts;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end regression test for the letterhead ("Kopfbogen").
 *
 * <p>The sample is an anonymised public-authority letter template. Under
 * docx4j-export-fo 11.5.9 its entire letterhead is missing from the PDF: the address column, the title, the
 * coat of arms and the fold marks are all VML shapes anchored to the page, and every one
 * of them is dropped -- three of them with nothing logged at all.
 *
 * <p>The expected coordinates are Word's own, read out of the PDF Word produced from the
 * same file.
 */
class KopfbogenConversionTest {

	private static final String SAMPLE = "/freigabe_antragsteller_auto.docx";

	/** Left edge of the address column, per Word. */
	private static final float COLUMN_LEFT_PT = 467.75f;
	private static final float PAGE_WIDTH_PT = 595.3f;

	/** Where Word draws the header logo, in points from the top-left page corner. */
	private static final int LOGO_LEFT_PT = 455;
	private static final int LOGO_TOP_PT = 40;
	private static final int LOGO_RIGHT_PT = 512;
	private static final int LOGO_BOTTOM_PT = 98;

	/** Anything below this counts as ink rather than paper. */
	private static final int INK_THRESHOLD = 160;

	private static byte[] pdf;

	/**
	 * Point this at a directory holding the fonts the sample names (Open Sans) to have
	 * the pagination assertion actually run instead of being skipped:
	 * {@code mvn test -Dexportfox.test.fontDir=/path/to/fonts}
	 */
	private static final String FONT_DIR_PROPERTY = "exportfox.test.fontDir";

	@BeforeAll
	static void convert() throws Exception {
		ConversionOptions options = new ConversionOptions();
		String fontDir = System.getProperty(FONT_DIR_PROPERTY);
		if (fontDir != null && !fontDir.isBlank()) {
			options.addFontDirectory(new File(fontDir));
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (InputStream docx = KopfbogenConversionTest.class.getResourceAsStream(SAMPLE)) {
			new DocxToPdfConverter(options).convert(docx, out);
		}
		pdf = out.toByteArray();
	}

	/**
	 * Only meaningful when the document's own fonts are installed: line breaking follows
	 * whatever font FOP ends up using, so a substituted one repaginates the whole letter.
	 * Word produced three pages with Open Sans.
	 */
	@Test
	void producesTheSameNumberOfPagesAsWord() throws IOException {
		assumeTrue(PhysicalFonts.get("open sans regular") != null,
				"Open Sans is not installed, so pagination cannot be compared with Word's");

		assertEquals(3, pageCount());
	}

	@Test
	void producesAMultiPageLetterWhateverFontsAreAvailable() throws IOException {
		assertTrue(pageCount() >= 2, "expected the letter to run to more than one page");
	}

	@Test
	void firstPageCarriesTheAddressColumn() throws IOException {
		String page = textOf(1);

		// Each of these lives in "Text Box 7", a v:shape anchored to the page.
		assertTrue(page.contains("Telefon"), page);
		assertTrue(page.contains("Landeskasse Musterstadt"), page);
		assertTrue(page.contains("DE00000000000000000000"), page);
		assertTrue(page.contains("Musterbrücke"), page);
	}

	@Test
	void addressColumnSitsInTheRightMargin() throws IOException {
		List<Float> xs = xPositionsOf(1, "Landeskasse");

		assertFalse(xs.isEmpty(), "the address column did not reach the PDF");
		float x = xs.get(0);
		assertTrue(x > COLUMN_LEFT_PT - 5 && x < PAGE_WIDTH_PT,
				"expected the column at about " + COLUMN_LEFT_PT + "pt, found it at " + x);
	}

	@Test
	void everyPageRepeatsTheLetterheadTitle() throws IOException {
		for (int page = 1; page <= pageCount(); page++) {
			assertTrue(textOf(page).contains("Musterbehörde Musterstadt"),
					"page " + page + " is missing the letterhead title");
		}
	}

	/**
	 * The logo is a WMF. FOP accepts one and reserves exactly the right amount of space,
	 * but draws nothing at all into it, so asserting that the graphic exists proves
	 * nothing -- the test has to look at whether pixels actually got painted.
	 */
	@Test
	void theHeaderLogoIsDrawn() throws IOException {
		assertTrue(hasInk(1, LOGO_LEFT_PT, LOGO_TOP_PT, LOGO_RIGHT_PT, LOGO_BOTTOM_PT),
				"the header logo's box on page 1 is blank");

		// A control: the upper left of page 1 is empty down to the return address line at
		// y = 141, so a test that only passed because the page came out black fails here.
		assertFalse(hasInk(1, 80, 20, 250, 120),
				"the blank reference area on page 1 is not blank");
	}

	@Test
	void bodyTextIsStillThere() throws IOException {
		String page = textOf(1);
		assertTrue(page.contains("Musterbescheid"), page);
		assertTrue(page.contains("Angaben zur Person"), page);
	}

	/**
	 * The salutation line uses the "Verfügung" style: a 10mm hanging indent, and a tab at
	 * the start of the run that puts the line back on the left indent. docx4j applies the
	 * indent but renders the tab as three fixed spaces, so without
	 * {@link org.docx4j.convert.out.fo.x.LeadingTabNormalizer} the line hangs 18pt out to
	 * the left of the address block underneath it. Word sets all six flush.
	 */
	@Test
	void theAddressBlockIsFlushDownItsLeftEdge() throws IOException {
		float salutation = onlyX(xPositionsOf(1, "$ANREDE_ANTRAGSTELLER$"));
		float nextLine = onlyX(xPositionsOf(1, "$NACHNAME_ANTRAGSTELLER$"));

		assertEquals(nextLine, salutation, 0.5f,
				"the first line of the address block is not flush with the rest");
	}

	private static float onlyX(List<Float> positions) {
		assertFalse(positions.isEmpty(), "line not found on the page");
		return positions.get(0);
	}

	/**
	 * Word keeps the paragraph carrying the section break in its own section, so the
	 * letter's closing sentence belongs on page 1.
	 */
	@Test
	void sectionBreakParagraphStaysOnItsOwnPage() throws IOException {
		assertTrue(textOf(1).contains("vorliegen."),
				"the section-break paragraph slipped to the next page:\n" + textOf(1));
	}

	private static int pageCount() throws IOException {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			return document.getNumberOfPages();
		}
	}

	private static String textOf(int page) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setStartPage(page);
			stripper.setEndPage(page);
			return stripper.getText(document);
		}
	}

	/** x coordinates, in points from the left page edge, of every occurrence of a word. */
	private static List<Float> xPositionsOf(int page, String word) throws IOException {
		final List<Float> found = new ArrayList<>();
		try (PDDocument document = Loader.loadPDF(pdf)) {
			PDFTextStripper stripper = new PDFTextStripper() {
				@Override
				protected void writeString(String text, List<TextPosition> positions) {
					if (text.contains(word) && !positions.isEmpty()) {
						found.add(positions.get(0).getXDirAdj());
					}
				}
			};
			stripper.setStartPage(page);
			stripper.setEndPage(page);
			stripper.getText(document);
		}
		return found;
	}

	/** True when the given rectangle of a rendered page contains any non-white pixel. */
	private static boolean hasInk(int page, int left, int top, int right, int bottom)
			throws IOException {

		try (PDDocument document = Loader.loadPDF(pdf)) {
			// 72 dpi, so one pixel is one point and the rectangle needs no conversion.
			BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(page - 1, 72);

			for (int y = top; y < Math.min(bottom, rendered.getHeight()); y++) {
				for (int x = left; x < Math.min(right, rendered.getWidth()); x++) {
					int rgb = rendered.getRGB(x, y);
					int brightness = Math.min(Math.min((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF), rgb & 0xFF);
					if (brightness < INK_THRESHOLD) {
						return true;
					}
				}
			}
			return false;
		}
	}
}

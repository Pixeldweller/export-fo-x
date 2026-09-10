package org.docx4j.convert.out.fo.x;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MetafileConverterTest {

	/** The placeholder mark that stands in for the sample letterhead's logo. */
	private static byte[] logo() throws Exception {
		try (InputStream in = MetafileConverterTest.class.getResourceAsStream("/placeholder-logo.wmf")) {
			return in.readAllBytes();
		}
	}

	@Test
	void recognisesAPlaceableWmf() throws Exception {
		assertTrue(MetafileConverter.isMetafile(logo()));
	}

	@Test
	void convertsWmfToSvgSoFopCanDrawIt() throws Exception {
		// FOP accepts the WMF and reports no error, but Batik's WMF transcoder implements
		// too little of GDI to draw this one -- the logo comes out an empty box.
		MetafileConverter.Result result = MetafileConverter.convert(logo(), "wmf");

		assertTrue(result.isConverted());
		assertEquals("svg", result.getExtension());

		String svg = new String(result.getBytes(), StandardCharsets.UTF_8);
		assertTrue(svg.contains("<svg"), "expected SVG, got: " + svg.substring(0, 80));
		// The DOCTYPE must go: left in, the parser tries to fetch the DTD from w3.org at
		// render time, which stalls or fails on a machine with no internet access.
		assertFalse(svg.contains("<!DOCTYPE"), "the SVG DTD reference should be stripped");
	}

	@Test
	void leavesOtherImageFormatsAlone() {
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0};

		assertFalse(MetafileConverter.isMetafile(png));

		MetafileConverter.Result result = MetafileConverter.convert(png, "png");
		assertFalse(result.isConverted());
		assertArrayEquals(png, result.getBytes());
		assertEquals("png", result.getExtension());
	}

	@Test
	void shortOrEmptyInputIsNotMistakenForAMetafile() {
		assertFalse(MetafileConverter.isMetafile(null));
		assertFalse(MetafileConverter.isMetafile(new byte[0]));
		assertFalse(MetafileConverter.isMetafile(new byte[] {1, 2, 3}));
	}

	@Test
	void recognisesEmfByItsSignature() {
		byte[] emf = new byte[120];
		emf[0] = 1; // EMR_HEADER
		// " EMF" at offset 40, little endian
		emf[40] = ' '; emf[41] = 'E'; emf[42] = 'M'; emf[43] = 'F';
		assertTrue(MetafileConverter.isMetafile(emf));

		// No raster inside, so it comes back untouched rather than mangled.
		assertFalse(MetafileConverter.convert(emf, "emf").isConverted());
	}
}

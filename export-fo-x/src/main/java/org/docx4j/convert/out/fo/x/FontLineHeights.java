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

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.io.File;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.docx4j.fonts.PhysicalFont;
import org.docx4j.fonts.PhysicalFonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How tall one single-spaced line of a given font is, as a multiple of the font size.
 *
 * <p>Word's "multiple" line spacing (1.5 lines, double, ...) multiplies the font's own
 * line height -- ascent + descent + line gap -- not the point size. The two differ a lot
 * between typefaces: Liberation Sans is about 1.15em, Open Sans about 1.36em. Over a page
 * of 1.5-spaced 11pt text that is the difference between three pages and four.
 *
 * <p>Values are cached per font name; an unknown font yields
 * {@link #UNKNOWN_RATIO}, which leaves the spacing exactly as docx4j would have emitted it.
 */
public final class FontLineHeights {

	private static final Logger log = LoggerFactory.getLogger(FontLineHeights.class);

	/** Returned when the font cannot be measured: means "change nothing". */
	public static final double UNKNOWN_RATIO = 1.0d;

	/** Measuring at a large size keeps rounding out of the ratio. */
	private static final float MEASUREMENT_SIZE = 1000f;

	private static final FontRenderContext FRC = new FontRenderContext(null, false, true);

	private static final Map<String, Double> CACHE = new ConcurrentHashMap<>();

	private FontLineHeights() {
	}

	/**
	 * @param fontName a font name as written in the docx, eg "Open Sans"
	 * @return line height as a multiple of the font size, or {@link #UNKNOWN_RATIO}
	 */
	public static double ratioFor(String fontName) {
		if (fontName == null || fontName.isBlank()) {
			return UNKNOWN_RATIO;
		}
		return CACHE.computeIfAbsent(fontName.toLowerCase(Locale.ROOT), FontLineHeights::measure);
	}

	private static double measure(String lowerCaseName) {

		PhysicalFont physical = lookup(lowerCaseName);
		if (physical == null) {
			log.debug("No physical font for '{}'; leaving its line spacing alone", lowerCaseName);
			return UNKNOWN_RATIO;
		}
		URI uri = physical.getEmbeddedURI();
		if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
			return UNKNOWN_RATIO;
		}
		try {
			Font font = Font.createFont(Font.TRUETYPE_FONT, new File(uri)).deriveFont(MEASUREMENT_SIZE);
			LineMetrics metrics = font.getLineMetrics("Hg", FRC);
			double ratio = (metrics.getAscent() + metrics.getDescent() + metrics.getLeading())
					/ MEASUREMENT_SIZE;
			if (ratio <= 0 || ratio > 3) {
				log.debug("Implausible line height ratio {} for '{}'", ratio, lowerCaseName);
				return UNKNOWN_RATIO;
			}
			log.debug("Line height ratio for '{}' is {}", lowerCaseName, ratio);
			return ratio;
		} catch (Exception e) {
			log.debug("Could not measure '{}': {}", lowerCaseName, e.toString());
			return UNKNOWN_RATIO;
		}
	}

	/**
	 * docx4j registers physical fonts under names like "open sans regular", so a
	 * document's plain "Open Sans" needs the style suffix appended before it matches.
	 */
	private static PhysicalFont lookup(String lowerCaseName) {
		PhysicalFont exact = PhysicalFonts.get(lowerCaseName);
		if (exact != null) {
			return exact;
		}
		for (String suffix : new String[] {" regular", " bold", " italic"}) {
			PhysicalFont styled = PhysicalFonts.get(lowerCaseName + suffix);
			if (styled != null) {
				return styled;
			}
		}
		return null;
	}
}

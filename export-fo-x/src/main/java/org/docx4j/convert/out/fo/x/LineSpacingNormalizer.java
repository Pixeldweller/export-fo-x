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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.docx4j.TraversalUtil;
import org.docx4j.model.PropertyResolver;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.wml.ContentAccessor;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.R;
import org.docx4j.wml.RFonts;
import org.docx4j.wml.RPr;
import org.docx4j.wml.RPrAbstract;
import org.docx4j.wml.STLineSpacingRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites Word's "multiple" line spacing into a value XSL-FO reproduces faithfully.
 *
 * <p>{@code <w:spacing w:line="360" w:lineRule="auto"/>} means 1.5 <em>lines</em>, and a
 * line in Word is the font's own height (ascent + descent + line gap). docx4j maps it
 * straight to {@code line-height="150%"} -- ignoring {@code w:lineRule} entirely -- and a
 * percentage in XSL-FO is relative to the <em>font size</em>. For Open Sans, whose natural
 * line is 1.36em, that makes every 1.5-spaced line 12pt instead of Word's 16.3pt: the text
 * is a quarter too tight, and by the second page the pagination has drifted.
 *
 * <p>The correction is to pre-multiply {@code w:line} by the font's line height ratio, so
 * that the percentage docx4j computes from it already accounts for the typeface. Doing it
 * on the WML rather than on the finished FO is what makes it safe: at this point
 * {@code w:lineRule} is still there to be read, so only "multiple" spacing is touched,
 * whereas {@code line-height="150%"} in the FO could equally have come from an exact
 * 18pt spacing.
 *
 * <p>Fonts the machine does not have are left alone: see {@link FontLineHeights}.
 * {@code w:lineRule} values {@code exact} and {@code atLeast} are also left alone -- docx4j
 * mistranslates those too, but a correction there needs the effective font size per line
 * and would change more documents than it fixed.
 */
public final class LineSpacingNormalizer {

	private static final Logger log = LoggerFactory.getLogger(LineSpacingNormalizer.class);

	/** Word states multiple spacing in 240ths of a line. */
	private static final double TWENTYFOURTHS = 240d;

	/** Below this, rescaling is not worth the churn. */
	private static final double SIGNIFICANT_DIFFERENCE = 0.005d;

	/** Packages already processed, so a second convert() cannot scale twice. */
	private static final Set<WordprocessingMLPackage> DONE =
			Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private LineSpacingNormalizer() {
	}

	/**
	 * @return how many paragraphs were adjusted
	 */
	public static int normalize(WordprocessingMLPackage wordMLPackage) throws Docx4JException {

		if (wordMLPackage == null || wordMLPackage.getMainDocumentPart() == null) {
			return 0;
		}
		if (!DONE.add(wordMLPackage)) {
			log.debug("Line spacing already normalized for this package");
			return 0;
		}

		PropertyResolver resolver = wordMLPackage.getMainDocumentPart().getPropertyResolver();

		int adjusted = 0;
		for (ContentAccessor part : partsToVisit(wordMLPackage)) {
			for (P paragraph : collectParagraphs(part)) {
				if (adjust(paragraph, resolver)) {
					adjusted++;
				}
			}
		}
		if (adjusted > 0) {
			log.debug("Rescaled multiple line spacing on {} paragraph(s)", adjusted);
		}
		return adjusted;
	}

	private static List<ContentAccessor> partsToVisit(WordprocessingMLPackage pkg) {
		List<ContentAccessor> parts = new ArrayList<>();
		parts.add(pkg.getMainDocumentPart());
		// Headers and footers matter as much as the body: a letterhead's address column
		// lives in a header text box and is spaced the same way.
		for (Part part : pkg.getParts().getParts().values()) {
			if (part instanceof HeaderPart || part instanceof FooterPart) {
				parts.add((ContentAccessor) part);
			}
		}
		return parts;
	}

	private static List<P> collectParagraphs(ContentAccessor root) {
		final List<P> paragraphs = new ArrayList<>();
		// TraversalUtil descends into w:pict, v:textbox and mc:AlternateContent, so
		// paragraphs inside a letterhead's text boxes are reached too.
		new TraversalUtil(root, new TraversalUtil.CallbackImpl() {
			@Override
			public List<Object> apply(Object o) {
				if (o instanceof P) {
					paragraphs.add((P) o);
				}
				return null;
			}
		});
		return paragraphs;
	}

	private static boolean adjust(P paragraph, PropertyResolver resolver) {

		PPr effective;
		try {
			effective = resolver.getEffectivePPr(paragraph.getPPr());
		} catch (RuntimeException e) {
			log.debug("Could not resolve paragraph properties: {}", e.toString());
			return false;
		}
		if (effective == null || effective.getSpacing() == null) {
			return false;
		}
		PPrBase.Spacing effectiveSpacing = effective.getSpacing();
		BigInteger line = effectiveSpacing.getLine();
		if (line == null || line.signum() <= 0) {
			return false;
		}
		// Absent lineRule means "auto", ie multiple.
		STLineSpacingRule rule = effectiveSpacing.getLineRule();
		if (rule != null && !STLineSpacingRule.AUTO.equals(rule)) {
			return false;
		}

		double ratio = FontLineHeights.ratioFor(fontNameOf(paragraph, resolver));
		if (Math.abs(ratio - FontLineHeights.UNKNOWN_RATIO) < SIGNIFICANT_DIFFERENCE) {
			return false;
		}

		BigInteger scaled = BigInteger.valueOf(Math.round(line.doubleValue() * ratio));
		writeDirectSpacing(paragraph, effectiveSpacing, scaled);

		if (log.isTraceEnabled()) {
			log.trace("line {} -> {} ({} lines x {} em)", line, scaled,
					line.doubleValue() / TWENTYFOURTHS, ratio);
		}
		return true;
	}

	/**
	 * Writes the corrected spacing as direct formatting, so it wins over whatever the
	 * paragraph's style says. The already-resolved before/after values are copied along
	 * with it, so replacing the style's spacing element loses nothing.
	 */
	private static void writeDirectSpacing(P paragraph, PPrBase.Spacing effectiveSpacing,
			BigInteger scaledLine) {

		PPr pPr = paragraph.getPPr();
		if (pPr == null) {
			pPr = new PPr();
			paragraph.setPPr(pPr);
			pPr.setParent(paragraph);
		}
		PPrBase.Spacing spacing = pPr.getSpacing();
		if (spacing == null) {
			spacing = new PPrBase.Spacing();
			pPr.setSpacing(spacing);
			spacing.setParent(pPr);
		}
		if (spacing.getBefore() == null) {
			spacing.setBefore(effectiveSpacing.getBefore());
		}
		if (spacing.getAfter() == null) {
			spacing.setAfter(effectiveSpacing.getAfter());
		}
		spacing.setLine(scaledLine);
		spacing.setLineRule(STLineSpacingRule.AUTO);
	}

	/**
	 * The font this paragraph's lines are measured against. Word uses the tallest run on
	 * each line; the paragraph's effective ascii font is a good enough stand-in, and for
	 * multiple spacing only the typeface matters, not the size.
	 */
	private static String fontNameOf(P paragraph, PropertyResolver resolver) {

		RPr runRPr = firstRunRPr(paragraph);
		try {
			RPr effective = resolver.getEffectiveRPr(runRPr, paragraph.getPPr());
			String name = asciiFont(effective);
			if (name != null) {
				return name;
			}
		} catch (RuntimeException e) {
			log.debug("Could not resolve run properties: {}", e.toString());
		}
		String fromRun = asciiFont(runRPr);
		if (fromRun != null) {
			return fromRun;
		}
		return paragraph.getPPr() == null ? null : asciiFont(paragraph.getPPr().getRPr());
	}

	/** The first run that states a font; null when no run does. */
	private static RPr firstRunRPr(P paragraph) {
		for (Object o : paragraph.getContent()) {
			Object unwrapped = org.docx4j.XmlUtils.unwrap(o);
			if (unwrapped instanceof R && ((R) unwrapped).getRPr() != null) {
				return ((R) unwrapped).getRPr();
			}
		}
		return null;
	}

	private static String asciiFont(RPrAbstract rPr) {
		if (rPr == null) {
			return null;
		}
		RFonts fonts = rPr.getRFonts();
		if (fonts == null) {
			return null;
		}
		if (fonts.getAscii() != null) {
			return fonts.getAscii();
		}
		return fonts.getHAnsi();
	}
}

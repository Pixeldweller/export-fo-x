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
import java.util.List;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.ParaRPr;
import org.docx4j.wml.STLineSpacingRule;
import org.docx4j.wml.SectPr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves a section break onto a paragraph of its own, so the paragraph that declared it
 * stays in the section it belongs to.
 *
 * <p>ECMA-376 §17.6.17: a {@code w:sectPr} inside {@code w:p/w:pPr} makes that paragraph
 * the <em>last</em> paragraph of the section it describes. docx4j's
 * ConversionSectionWrapperFactory closes the section <em>before</em> that paragraph and
 * then adds it to the following one, so its text moves to the next section's first page.
 * In a letter whose closing sentence sits on the section-break paragraph, that sentence
 * jumps to page 2.
 *
 * <p>Rather than shadow a docx4j-core class -- which would depend on classpath order and
 * silently change HTML output too -- this rewrites the document: the text stays where it
 * was, in a paragraph with no {@code sectPr}, and the {@code sectPr} moves to an empty
 * paragraph inserted straight after it. That marker paragraph is given a 1pt font and an
 * exact 1-twip line, so it occupies no visible height.
 *
 * <p>The transform is idempotent: a marker paragraph has no content, so a second pass
 * leaves it alone.
 */
public final class SectPrParagraphNormalizer {

	private static final Logger log = LoggerFactory.getLogger(SectPrParagraphNormalizer.class);

	/** 1pt, expressed in half-points as w:sz wants it. */
	private static final BigInteger MARKER_FONT_HALF_POINTS = BigInteger.valueOf(2);
	/** One twip, as an exact line height. */
	private static final BigInteger MARKER_LINE_TWIPS = BigInteger.ONE;

	private SectPrParagraphNormalizer() {
	}

	/**
	 * @return how many section breaks were moved
	 * @throws Docx4JException if the main document part cannot be read
	 */
	public static int normalize(WordprocessingMLPackage wordMLPackage) throws Docx4JException {

		if (wordMLPackage == null || wordMLPackage.getMainDocumentPart() == null) {
			return 0;
		}
		List<Object> content = wordMLPackage.getMainDocumentPart().getContents().getBody().getContent();

		int moved = 0;
		// Backwards, so the inserts don't disturb the indices still to visit.
		for (int i = content.size() - 1; i >= 0; i--) {

			Object unwrapped = XmlUtils.unwrap(content.get(i));
			if (!(unwrapped instanceof P)) {
				continue;
			}
			P paragraph = (P) unwrapped;
			PPr pPr = paragraph.getPPr();
			if (pPr == null || pPr.getSectPr() == null) {
				continue;
			}
			if (paragraph.getContent().isEmpty()) {
				continue; // already only a section marker
			}

			SectPr sectPr = pPr.getSectPr();
			pPr.setSectPr(null);
			content.add(i + 1, createMarker(sectPr));
			moved++;
		}

		if (moved > 0) {
			log.debug("Moved {} section break(s) onto a paragraph of their own", moved);
		}
		return moved;
	}

	private static P createMarker(SectPr sectPr) {

		PPr pPr = new PPr();
		pPr.setSectPr(sectPr);

		PPrBase.Spacing spacing = new PPrBase.Spacing();
		spacing.setBefore(BigInteger.ZERO);
		spacing.setAfter(BigInteger.ZERO);
		spacing.setLine(MARKER_LINE_TWIPS);
		spacing.setLineRule(STLineSpacingRule.EXACT);
		pPr.setSpacing(spacing);

		HpsMeasure size = new HpsMeasure();
		size.setVal(MARKER_FONT_HALF_POINTS);
		ParaRPr rPr = new ParaRPr();
		rPr.setSz(size);
		rPr.setSzCs(size);
		pPr.setRPr(rPr);

		P marker = new P();
		marker.setPPr(pPr);
		pPr.setParent(marker);
		return marker;
	}
}

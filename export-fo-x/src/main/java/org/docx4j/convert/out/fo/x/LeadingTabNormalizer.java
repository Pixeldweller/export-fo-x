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
import org.docx4j.XmlUtils;
import org.docx4j.model.PropertyResolver;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.wml.CTTabStop;
import org.docx4j.wml.ContentAccessor;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.R;
import org.docx4j.wml.STTabJc;
import org.docx4j.wml.Tabs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a leading {@code w:tab} in a paragraph with a hanging indent into the
 * first-line indent it actually produces.
 *
 * <p>The pattern is standard in German official correspondence -- the "Verfügung"
 * paragraph style:
 *
 * <pre>
 * &lt;w:pPr&gt;
 *   &lt;w:tabs&gt;&lt;w:tab w:val="left" w:pos="0"/&gt;&lt;/w:tabs&gt;
 *   &lt;w:ind w:hanging="567"/&gt;
 * &lt;/w:pPr&gt;
 * ... &lt;w:r&gt;&lt;w:tab/&gt;&lt;w:t&gt;Text&lt;/w:t&gt;&lt;/w:r&gt;
 * </pre>
 *
 * <p>Word starts the first line 567 twips left of the indent and the tab then jumps to
 * the stop at position 0, so the line ends up flush with every other line. docx4j applies
 * the hanging indent but renders <em>every</em> {@code w:tab} as three non-breaking
 * spaces regardless of the paragraph's tab stops -- docx2fo.xslt says as much in a
 * comment ("simple-minded approach ... until our document model can do better"). The
 * compensation is therefore lost and the first line hangs out to the left.
 *
 * <p>XSL-FO has no tab stops to map onto, so the fix happens on the WML: the leading tab
 * is dropped and the first-line indent is set to where that tab would have landed. Only
 * paragraphs with <em>both</em> a hanging indent and a leading tab are touched -- exactly
 * the ones that come out wrong today.
 *
 * <p>The stop is found the way Word finds it: the nearest left tab stop after the start
 * of the line, where a hanging indent contributes an implicit stop at the left indent.
 * Centre, right and decimal stops are left alone, since those cannot be expressed as an
 * indent; nor are Word's default tab stops considered, because the implicit stop always
 * wins for this pattern.
 */
public final class LeadingTabNormalizer {

	private static final Logger log = LoggerFactory.getLogger(LeadingTabNormalizer.class);

	/** Packages already processed, so a second convert() cannot strip a second tab. */
	private static final Set<WordprocessingMLPackage> DONE =
			Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private LeadingTabNormalizer() {
	}

	/**
	 * @return how many paragraphs were adjusted
	 */
	public static int normalize(WordprocessingMLPackage wordMLPackage) throws Docx4JException {

		if (wordMLPackage == null || wordMLPackage.getMainDocumentPart() == null) {
			return 0;
		}
		if (!DONE.add(wordMLPackage)) {
			log.debug("Leading tabs already normalized for this package");
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
			log.debug("Resolved a leading tab into a first-line indent on {} paragraph(s)", adjusted);
		}
		return adjusted;
	}

	private static List<ContentAccessor> partsToVisit(WordprocessingMLPackage pkg) {
		List<ContentAccessor> parts = new ArrayList<>();
		parts.add(pkg.getMainDocumentPart());
		for (Part part : pkg.getParts().getParts().values()) {
			if (part instanceof HeaderPart || part instanceof FooterPart) {
				parts.add((ContentAccessor) part);
			}
		}
		return parts;
	}

	private static List<P> collectParagraphs(ContentAccessor root) {
		final List<P> paragraphs = new ArrayList<>();
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
		if (effective == null || effective.getInd() == null) {
			return false;
		}

		long hanging = hangingTwips(effective.getInd());
		if (hanging <= 0) {
			return false;
		}
		R runWithTab = runStartingWithTab(paragraph);
		if (runWithTab == null) {
			return false;
		}

		long left = value(effective.getInd().getLeft());
		long lineStart = left - hanging;

		Long stop = firstLeftStopAfter(effective.getTabs(), lineStart, left);
		if (stop == null) {
			log.debug("Leading tab with no usable left tab stop after {} twips; left as is", lineStart);
			return false;
		}

		removeLeadingTab(runWithTab);
		writeFirstLineIndent(paragraph, effective.getInd(), stop - left);

		if (log.isTraceEnabled()) {
			log.trace("hanging {} + leading tab -> first line at {} twips from the indent",
					hanging, stop - left);
		}
		return true;
	}

	/** A hanging indent, however it was written: {@code w:hanging} or a negative {@code w:firstLine}. */
	private static long hangingTwips(PPrBase.Ind ind) {
		long hanging = value(ind.getHanging());
		if (hanging > 0) {
			return hanging;
		}
		long firstLine = value(ind.getFirstLine());
		return firstLine < 0 ? -firstLine : 0;
	}

	/**
	 * The first run of the paragraph, if the first thing it renders is a tab.
	 *
	 * <p>Elements that produce no output of their own -- bookmarks, proofing marks,
	 * revision marks -- are skipped, since Word does not treat them as content either.
	 */
	private static R runStartingWithTab(P paragraph) {
		for (Object o : paragraph.getContent()) {
			Object unwrapped = XmlUtils.unwrap(o);
			if (isInvisible(unwrapped)) {
				continue;
			}
			if (!(unwrapped instanceof R)) {
				return null; // something else comes first, so the tab is not leading
			}
			R run = (R) unwrapped;
			for (Object child : run.getContent()) {
				Object unwrappedChild = XmlUtils.unwrap(child);
				if (isInvisible(unwrappedChild)) {
					continue;
				}
				return unwrappedChild instanceof R.Tab ? run : null;
			}
			return null; // an empty run: no leading tab here
		}
		return null;
	}

	private static boolean isInvisible(Object o) {
		return o instanceof org.docx4j.wml.ProofErr
				|| o instanceof org.docx4j.wml.CTBookmark
				|| o instanceof org.docx4j.wml.CTMarkupRange
				|| o instanceof org.docx4j.wml.RPr
				|| o instanceof org.docx4j.wml.PPr;
	}

	private static void removeLeadingTab(R run) {
		for (int i = 0; i < run.getContent().size(); i++) {
			Object unwrapped = XmlUtils.unwrap(run.getContent().get(i));
			if (isInvisible(unwrapped)) {
				continue;
			}
			if (unwrapped instanceof R.Tab) {
				run.getContent().remove(i);
			}
			return;
		}
	}

	/**
	 * Where the tab lands: the nearest left tab stop beyond the start of the line.
	 *
	 * @param lineStart position the first line begins at, ie left indent minus hanging
	 * @param left      the left indent, which a hanging indent turns into an implicit stop
	 * @return the stop's position in twips, or null if there is none we can express
	 */
	private static Long firstLeftStopAfter(Tabs tabs, long lineStart, long left) {

		Long best = left > lineStart ? Long.valueOf(left) : null;   // the implicit stop

		if (tabs != null) {
			for (CTTabStop stop : tabs.getTab()) {
				if (stop.getPos() == null || !isLeftAligned(stop.getVal())) {
					continue;
				}
				long pos = stop.getPos().longValue();
				if (pos > lineStart && (best == null || pos < best)) {
					best = pos;
				}
			}
		}
		return best;
	}

	private static boolean isLeftAligned(STTabJc alignment) {
		// An absent w:val means "left"; CLEAR removes an inherited stop rather than adding one.
		return alignment == null || STTabJc.LEFT.equals(alignment);
	}

	/**
	 * Writes the resulting first-line offset as direct formatting, so it beats whatever
	 * the paragraph's style says. The resolved left/right indents are copied along with
	 * it, so replacing the style's w:ind loses nothing.
	 *
	 * <p>The offset is written as {@code w:hanging}, never as {@code w:firstLine}, for
	 * two reasons. Clearing an inherited value is not possible with null -- docx4j merges
	 * an absent attribute as "inherit" ({@code StyleUtil.apply} returns the style's value
	 * whenever the direct one is null) -- so the hanging indent has to be overwritten with
	 * a real number. And {@code Indent.setXslFO} gives {@code w:hanging} precedence over
	 * {@code w:firstLine}, so leaving a hanging value in place would mask the first-line
	 * one anyway.
	 *
	 * <p>That works out because the offset is never positive: the tab stop is picked from
	 * candidates at or before the left indent, so {@code -offset} is always a valid,
	 * non-negative {@code w:hanging}.
	 */
	private static void writeFirstLineIndent(P paragraph, PPrBase.Ind effectiveInd, long offset) {

		PPr pPr = paragraph.getPPr();
		if (pPr == null) {
			pPr = new PPr();
			paragraph.setPPr(pPr);
			pPr.setParent(paragraph);
		}
		PPrBase.Ind ind = pPr.getInd();
		if (ind == null) {
			ind = new PPrBase.Ind();
			pPr.setInd(ind);
			ind.setParent(pPr);
		}
		if (ind.getLeft() == null) {
			ind.setLeft(effectiveInd.getLeft());
		}
		if (ind.getRight() == null) {
			ind.setRight(effectiveInd.getRight());
		}

		ind.setHanging(BigInteger.valueOf(-offset));
		ind.setFirstLine(null);
	}

	private static long value(BigInteger v) {
		return v == null ? 0L : v.longValue();
	}
}

/*
 * export-fo-x sample application.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pixeldweller.export.fox.sample.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.docx4j.TraversalUtil;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.wml.ContentAccessor;
import org.docx4j.wml.P;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds and fills {@code $NAME$} placeholders in a Word template.
 *
 * <p>Headers and footers are searched as well as the body: in the sample letter the
 * caseworker's name, telephone number and the date all live in a header text box.
 *
 * <p>Everything works on the paragraph's whole text rather than on individual
 * {@code w:t} elements, because Word splits runs wherever it likes -- see
 * {@link ParagraphText}.
 */
public final class PlaceholderTemplate {

	private static final Logger log = LoggerFactory.getLogger(PlaceholderTemplate.class);

	/** {@code $NAME$}: letters, digits and underscores between two dollar signs. */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$([A-Za-z0-9_]+)\\$");

	/** Stops a pathological template from looping forever. */
	private static final int MAX_REPLACEMENTS_PER_PARAGRAPH = 500;

	private PlaceholderTemplate() {
	}

	/**
	 * Every distinct placeholder in the template, in the order it is first met, each with
	 * a suggested value.
	 */
	public static List<Placeholder> scan(WordprocessingMLPackage wordMLPackage) throws Docx4JException {

		Set<String> names = new LinkedHashSet<>();
		for (ContentAccessor part : partsOf(wordMLPackage)) {
			for (P paragraph : paragraphsOf(part)) {
				Matcher m = PLACEHOLDER.matcher(ParagraphText.of(paragraph).asString());
				while (m.find()) {
					names.add(m.group(1));
				}
			}
		}

		List<Placeholder> placeholders = new ArrayList<>(names.size());
		for (String name : names) {
			placeholders.add(new Placeholder(name,
					PlaceholderDefaults.labelFor(name),
					PlaceholderDefaults.forName(name)));
		}
		log.debug("Found {} placeholder(s)", placeholders.size());
		return placeholders;
	}

	/**
	 * Substitutes the given values. A placeholder with no entry in {@code values} is left
	 * standing rather than blanked, so a half-filled form is obvious in the PDF instead of
	 * quietly losing text.
	 *
	 * @return how many placeholder occurrences were replaced
	 */
	public static int stamp(WordprocessingMLPackage wordMLPackage, Map<String, String> values)
			throws Docx4JException {

		if (values == null || values.isEmpty()) {
			return 0;
		}
		int replaced = 0;
		for (ContentAccessor part : partsOf(wordMLPackage)) {
			for (P paragraph : paragraphsOf(part)) {
				replaced += stampParagraph(paragraph, values);
			}
		}
		log.debug("Replaced {} placeholder occurrence(s)", replaced);
		return replaced;
	}

	private static int stampParagraph(P paragraph, Map<String, String> values) {

		int replaced = 0;
		// The text is rebuilt after every substitution: a replacement changes the offsets
		// of everything after it, and a value may itself be shorter or longer than the
		// placeholder. Paragraphs are small, so the cost does not matter.
		for (int guard = 0; guard < MAX_REPLACEMENTS_PER_PARAGRAPH; guard++) {

			ParagraphText text = ParagraphText.of(paragraph);
			Matcher m = PLACEHOLDER.matcher(text.asString());

			String replacement = null;
			int start = -1;
			int end = -1;
			while (m.find()) {
				String value = values.get(m.group(1));
				if (value != null) {
					replacement = value;
					start = m.start();
					end = m.end();
					break;
				}
			}
			if (replacement == null) {
				return replaced;
			}
			text.replace(start, end, replacement);
			replaced++;
		}
		log.warn("Gave up after {} replacements in one paragraph", MAX_REPLACEMENTS_PER_PARAGRAPH);
		return replaced;
	}

	private static List<ContentAccessor> partsOf(WordprocessingMLPackage pkg) {
		List<ContentAccessor> parts = new ArrayList<>();
		parts.add(pkg.getMainDocumentPart());
		for (Part part : pkg.getParts().getParts().values()) {
			if (part instanceof HeaderPart || part instanceof FooterPart) {
				parts.add((ContentAccessor) part);
			}
		}
		return parts;
	}

	private static List<P> paragraphsOf(ContentAccessor root) {
		final List<P> paragraphs = new ArrayList<>();
		// TraversalUtil descends into w:pict and v:textbox, so the letterhead's text
		// boxes are covered too.
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
}

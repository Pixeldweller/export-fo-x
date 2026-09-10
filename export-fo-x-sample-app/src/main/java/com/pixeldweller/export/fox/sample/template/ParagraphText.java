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
import java.util.List;

import org.docx4j.TraversalUtil;
import org.docx4j.wml.P;
import org.docx4j.wml.Text;

/**
 * A paragraph's text seen as one string, with a way back to the {@code w:t} elements it
 * came from.
 *
 * <p>Word splits a run whenever anything about it changes -- a spell-check marker, a
 * revision id, a different font -- and it does so without regard for what the text says.
 * In the sample template {@code $STAATSANGEHOERIGKEIT_ANTRAGSTELLER$} is spread over
 * three runs as {@code $STAAT} + {@code S} + {@code ANGEHOERIGKEIT_ANTRAGSTELLER$}, so
 * anything that looks for placeholders one {@code w:t} at a time simply misses it.
 *
 * <p>docx4j's {@code VariablePrepare.joinupRuns} only merges runs whose formatting is
 * identical, which is not enough here.
 */
final class ParagraphText {

	private final List<Text> texts = new ArrayList<>();
	private final String joined;

	private ParagraphText(List<Text> texts) {
		this.texts.addAll(texts);
		StringBuilder sb = new StringBuilder();
		for (Text t : texts) {
			sb.append(value(t));
		}
		this.joined = sb.toString();
	}

	/** Collects the w:t elements of a paragraph, in reading order. */
	static ParagraphText of(P paragraph) {
		final List<Text> found = new ArrayList<>();
		new TraversalUtil(paragraph, new TraversalUtil.CallbackImpl() {
			@Override
			public List<Object> apply(Object o) {
				if (o instanceof Text) {
					found.add((Text) o);
				}
				return null;
			}
		});
		return new ParagraphText(found);
	}

	String asString() {
		return joined;
	}

	/**
	 * Replaces {@code [start, end)} of {@link #asString()} with {@code replacement},
	 * spreading the edit over however many {@code w:t} elements the range covers. The
	 * replacement takes the formatting of the first of them, which is what Word does
	 * when you type over a selection.
	 */
	void replace(int start, int end, String replacement) {

		int cursor = 0;
		boolean written = false;

		for (Text text : texts) {
			String value = value(text);
			int from = cursor;
			int to = cursor + value.length();
			cursor = to;

			if (to <= start || from >= end) {
				continue; // untouched
			}
			int cutFrom = Math.max(start - from, 0);
			int cutTo = Math.min(end - from, value.length());

			String head = value.substring(0, cutFrom);
			String tail = value.substring(cutTo);
			String middle = written ? "" : replacement;
			written = true;

			set(text, head + middle + tail);
		}
	}

	private static void set(Text text, String value) {
		text.setValue(value);
		// Without this, Word and every consumer after it collapse leading and trailing
		// spaces -- which is exactly what a stamped value often ends with.
		if (!value.equals(value.strip())) {
			text.setSpace("preserve");
		}
	}

	private static String value(Text text) {
		return text.getValue() == null ? "" : text.getValue();
	}
}

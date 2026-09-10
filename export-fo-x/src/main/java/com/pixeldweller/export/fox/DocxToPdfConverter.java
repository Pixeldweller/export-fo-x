/*
 * export-fo-x -- Weiterentwicklung von docx4j-export-fo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pixeldweller.export.fox;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

import org.docx4j.Docx4J;
import org.docx4j.convert.out.FOSettings;
import org.docx4j.convert.out.fo.renderers.FORendererApacheFOP;
import org.docx4j.convert.out.fo.x.FontCacheHome;
import org.docx4j.convert.out.fo.x.LeadingTabNormalizer;
import org.docx4j.convert.out.fo.x.LineSpacingNormalizer;
import org.docx4j.convert.out.fo.x.SectPrParagraphNormalizer;
import org.docx4j.fonts.PhysicalFonts;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DOCX to PDF, via XSL-FO and Apache FOP.
 *
 * <p>A thin, thread-safe front end over {@code Docx4J.toFO}. Using docx4j directly works
 * just as well -- export-fo-x is a drop-in replacement for docx4j-export-fo, so
 * {@code Docx4J.toPDF(pkg, out)} already picks up every fix in it. What this class adds
 * is the handful of steps a server deployment wants anyway: registering the fonts the
 * templates name, moving section breaks onto their own paragraph, and turning docx4j's
 * checked exceptions into one.
 *
 * <pre>
 * DocxToPdfConverter converter = new DocxToPdfConverter(
 *         new ConversionOptions().addFontDirectory(new File("/opt/app/fonts")));
 * converter.convert(docxStream, pdfStream);
 * </pre>
 */
public class DocxToPdfConverter {

	private static final Logger log = LoggerFactory.getLogger(DocxToPdfConverter.class);

	static {
		// Must happen before anything touches org.docx4j.fonts: the font cache is written
		// from a static initialiser, and with an unresolvable user.home that write lands on
		// a relative path -- or throws, taking the class down with it. See FontCacheHome.
		FontCacheHome.ensureUsable();
	}

	/** PhysicalFonts is process-global, so remember what has already been registered. */
	private static final Set<String> REGISTERED_FONT_DIRECTORIES = new HashSet<>();

	private final ConversionOptions options;

	public DocxToPdfConverter() {
		this(new ConversionOptions());
	}

	public DocxToPdfConverter(ConversionOptions options) {
		this.options = options == null ? new ConversionOptions() : options;
		registerFonts(this.options);
	}

	public ConversionOptions getOptions() {
		return options;
	}

	/**
	 * Reads a DOCX and writes a PDF. Neither stream is closed.
	 *
	 * @throws Docx4JException if the document cannot be read or rendered
	 */
	public void convert(InputStream docx, OutputStream pdf) throws Docx4JException {
		convert(WordprocessingMLPackage.load(docx), pdf);
	}

	/**
	 * Renders an already-loaded package.
	 *
	 * <p>Note that the package is modified in place when
	 * {@link ConversionOptions#isNormalizeSectionBreaks()} is on, and that docx4j attaches
	 * conversion state to it either way, so don't render the same instance twice
	 * concurrently.
	 */
	public void convert(WordprocessingMLPackage wordMLPackage, OutputStream pdf) throws Docx4JException {

		if (options.isNormalizeSectionBreaks()) {
			SectPrParagraphNormalizer.normalize(wordMLPackage);
		}
		if (options.isNormalizeLineSpacing()) {
			LineSpacingNormalizer.normalize(wordMLPackage);
		}
		if (options.isNormalizeLeadingTabs()) {
			LeadingTabNormalizer.normalize(wordMLPackage);
		}

		FOSettings settings = Docx4J.createFOSettings();
		settings.setOpcPackage(wordMLPackage);
		settings.setApacheFopMime(FOSettings.MIME_PDF);
		settings.setCustomFoRenderer(FORendererApacheFOP.getInstance());

		if (options.getFoDumpFile() != null) {
			settings.setFoDumpFile(options.getFoDumpFile());
		}
		if (options.getImageDirPath() != null) {
			settings.setImageDirPath(options.getImageDirPath());
		}

		Docx4J.toFO(settings, pdf, Docx4J.FLAG_NONE);
	}

	/**
	 * Registers every font file in the configured directories with docx4j.
	 *
	 * <p>{@code PhysicalFonts.addPhysicalFont} takes one font file, not a directory, so
	 * the directory is walked here. Registration is process-global and additive, hence
	 * the guard against doing the same directory twice.
	 */
	private static synchronized void registerFonts(ConversionOptions options) {

		for (File directory : options.getFontDirectories()) {

			String key = directory.getAbsolutePath();
			if (!REGISTERED_FONT_DIRECTORIES.add(key)) {
				continue;
			}
			if (!directory.isDirectory()) {
				log.warn("Font directory {} does not exist. The fonts the document asks for "
						+ "will be substituted, and since line breaking follows the "
						+ "substitute's metrics the text will reflow.", key);
				continue;
			}

			File[] files = directory.listFiles(DocxToPdfConverter::isFontFile);
			if (files == null || files.length == 0) {
				log.warn("No font files (.ttf/.otf/.ttc) in {}", key);
				continue;
			}
			int registered = 0;
			for (File font : files) {
				try {
					PhysicalFonts.addPhysicalFont(font.toURI());
					registered++;
				} catch (Exception e) {
					log.warn("Could not register font {}: {}", font.getName(), e.toString());
				}
			}
			log.info("Registered {} font file(s) from {}", registered, key);
		}
	}

	private static boolean isFontFile(File file) {
		String name = file.getName().toLowerCase(java.util.Locale.ROOT);
		return file.isFile()
				&& (name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc"));
	}
}

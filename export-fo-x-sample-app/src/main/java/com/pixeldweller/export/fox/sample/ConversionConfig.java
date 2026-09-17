/*
 * export-fo-x sample application.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pixeldweller.export.fox.sample;

import java.io.File;
import java.nio.file.Paths;

import com.pixeldweller.export.fox.ConversionOptions;
import com.pixeldweller.export.fox.DocxToPdfConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConversionConfig {

	private static final Logger log = LoggerFactory.getLogger(ConversionConfig.class);

	/** Name of the directory extracted fonts go into, under the temp directory. */
	private static final String EXTRACT_DIRECTORY_NAME = "export-fo-x-fonts";

	/**
	 * One converter for the whole application. It holds no per-document state -- each
	 * request loads its own package -- so a single instance serves every request.
	 */
	@Bean
	public DocxToPdfConverter docxToPdfConverter(ConversionProperties properties) {

		ConversionOptions options = new ConversionOptions()
				.setNormalizeSectionBreaks(properties.isNormalizeSectionBreaks())
				.setNormalizeLineSpacing(properties.isNormalizeLineSpacing())
				.setNormalizeLeadingTabs(properties.isNormalizeLeadingTabs())
				.setImageDirPath(blankToNull(properties.getImageDirPath()));

		for (String directory : properties.getFontDirectories()) {
			options.addFontDirectory(new File(directory));
		}
		File extracted = extractBundledFonts(properties);
		if (extracted != null) {
			options.addFontDirectory(extracted);
		}
		return new DocxToPdfConverter(options);
	}

	/**
	 * Fonts packaged inside the war or jar are copied out to a real directory first --
	 * see {@link FontResourceExtractor} for why reading them in place is not enough.
	 */
	private File extractBundledFonts(ConversionProperties properties) {

		String resourcePath = properties.getFontResourcePath();
		if (resourcePath == null || resourcePath.isBlank()) {
			return null;
		}
		File target = targetDirectory(properties);
		if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
			log.warn("Cannot create {} for the bundled fonts. On a host where only /tmp is "
					+ "writable, set export-fo-x.font-extract-directory (java.io.tmpdir is "
					+ "$CATALINA_BASE/temp under Tomcat, not /tmp).", target);
			return null;
		}
		return FontResourceExtractor.extract(resourcePath, target);
	}

	private File targetDirectory(ConversionProperties properties) {
		String configured = properties.getFontExtractDirectory();
		if (configured != null && !configured.isBlank()) {
			return new File(configured);
		}
		return Paths.get(System.getProperty("java.io.tmpdir"), EXTRACT_DIRECTORY_NAME).toFile();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}

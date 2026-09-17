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

import com.pixeldweller.export.fox.ConversionOptions;
import com.pixeldweller.export.fox.DocxToPdfConverter;
import jakarta.servlet.ServletContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConversionConfig {

	private static final Logger log = LoggerFactory.getLogger(ConversionConfig.class);

	/** Name of the directory extracted fonts go into, under the temp directory. */
	private static final String EXTRACT_DIRECTORY_NAME = "export-fo-x-fonts";

	/** Name of the directory images pulled out of a document go into. */
	private static final String IMAGE_DIRECTORY_NAME = "export-fo-x-images";

	/**
	 * One converter for the whole application. It holds no per-document state -- each
	 * request loads its own package -- so a single instance serves every request.
	 */
	@Bean
	public DocxToPdfConverter docxToPdfConverter(ConversionProperties properties,
			ObjectProvider<ServletContext> servletContext) {

		// The servlet container's temp directory is writable per the servlet spec, which
		// makes it the right default on a host where $CATALINA_BASE/temp is not and no
		// JVM options can be added.
		File containerTemp = containerTempDirectory(servletContext);

		ConversionOptions options = new ConversionOptions()
				.setNormalizeSectionBreaks(properties.isNormalizeSectionBreaks())
				.setNormalizeLineSpacing(properties.isNormalizeLineSpacing())
				.setNormalizeLeadingTabs(properties.isNormalizeLeadingTabs())
				.setImageDirPath(imageDirPath(properties, containerTemp));

		for (String directory : properties.getFontDirectories()) {
			options.addFontDirectory(new File(directory));
		}
		File extracted = extractBundledFonts(properties, containerTemp);
		if (extracted != null) {
			options.addFontDirectory(extracted);
		}
		return new DocxToPdfConverter(options);
	}

	/**
	 * Fonts packaged inside the war or jar are copied out to a real directory first --
	 * see {@link FontResourceExtractor} for why reading them in place is not enough.
	 */
	private File extractBundledFonts(ConversionProperties properties, File containerTemp) {

		String resourcePath = properties.getFontResourcePath();
		if (resourcePath == null || resourcePath.isBlank()) {
			return null;
		}
		File target = targetDirectory(properties, containerTemp);
		if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
			log.warn("Cannot create {} for the bundled fonts. On a host where only /tmp is "
					+ "writable, set export-fo-x.font-extract-directory (java.io.tmpdir is "
					+ "$CATALINA_BASE/temp under Tomcat, not /tmp).", target);
			return null;
		}
		return FontResourceExtractor.extract(resourcePath, target);
	}

	private File targetDirectory(ConversionProperties properties, File containerTemp) {
		String configured = properties.getFontExtractDirectory();
		if (configured != null && !configured.isBlank()) {
			return new File(configured);
		}
		File base = containerTemp != null
				? containerTemp
				: new File(System.getProperty("java.io.tmpdir"));
		return new File(base, EXTRACT_DIRECTORY_NAME);
	}

	private String imageDirPath(ConversionProperties properties, File containerTemp) {
		String configured = properties.getImageDirPath();
		if (configured != null && !configured.isBlank()) {
			return configured;
		}
		if (containerTemp == null) {
			return null; // docx4j falls back to java.io.tmpdir, which is right for a jar
		}
		File images = new File(containerTemp, IMAGE_DIRECTORY_NAME);
		if (!images.isDirectory() && !images.mkdirs() && !images.isDirectory()) {
			log.warn("Cannot create {} for extracted images; falling back to java.io.tmpdir",
					images);
			return null;
		}
		return images.getAbsolutePath();
	}

	/** The container's temp directory, or null outside a servlet container. */
	private File containerTempDirectory(ObjectProvider<ServletContext> servletContext) {
		ServletContext context = servletContext.getIfAvailable();
		if (context == null) {
			return null;
		}
		Object tempDir = context.getAttribute(ServletContext.TEMPDIR);
		return tempDir instanceof File ? (File) tempDir : null;
	}
}

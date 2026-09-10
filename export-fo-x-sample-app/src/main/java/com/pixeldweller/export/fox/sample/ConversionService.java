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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.UUID;

import com.pixeldweller.export.fox.ConversionOptions;
import com.pixeldweller.export.fox.DocxToPdfConverter;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversionService {

	private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

	/** A PDF is built in memory before it is sent, so cap what we accept. */
	private static final int INITIAL_BUFFER_BYTES = 256 * 1024;

	private final DocxToPdfConverter converter;
	private final ConversionProperties properties;

	public ConversionService(DocxToPdfConverter converter, ConversionProperties properties) {
		this.converter = converter;
		this.properties = properties;
	}

	/**
	 * @param docx     the uploaded document; closed by the caller
	 * @param filename used only for logging and for the FO dump's name
	 * @return the rendered PDF
	 */
	public byte[] toPdf(InputStream docx, String filename) throws Docx4JException {

		long started = System.currentTimeMillis();
		ByteArrayOutputStream pdf = new ByteArrayOutputStream(INITIAL_BUFFER_BYTES);

		DocxToPdfConverter perRequest = withFoDump(filename);
		perRequest.convert(docx, pdf);

		log.info("Converted {} ({} bytes PDF) in {} ms",
				filename, pdf.size(), System.currentTimeMillis() - started);
		return pdf.toByteArray();
	}

	/**
	 * The FO dump file is per-conversion, so when dumping is switched on the request gets
	 * its own converter rather than mutating the shared one.
	 */
	private DocxToPdfConverter withFoDump(String filename) {

		String directory = properties.getFoDumpDirectory();
		if (directory == null || directory.isBlank()) {
			return converter;
		}
		ConversionOptions options = converter.getOptions();
		ConversionOptions perRequest = new ConversionOptions()
				.setNormalizeSectionBreaks(options.isNormalizeSectionBreaks())
				.setNormalizeLineSpacing(options.isNormalizeLineSpacing())
				.setNormalizeLeadingTabs(options.isNormalizeLeadingTabs())
				.setFoDumpFile(dumpFile(directory, filename));
		for (File fontDirectory : options.getFontDirectories()) {
			perRequest.addFontDirectory(fontDirectory);
		}
		return new DocxToPdfConverter(perRequest);
	}

	private File dumpFile(String directory, String filename) {
		String safe = filename == null ? "document" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
		File file = Paths.get(directory, safe + "-" + UUID.randomUUID() + ".fo").toFile();
		try {
			java.nio.file.Files.createDirectories(file.getParentFile().toPath());
		} catch (IOException e) {
			log.warn("Could not create the FO dump directory {}: {}", directory, e.toString());
		}
		return file;
	}
}

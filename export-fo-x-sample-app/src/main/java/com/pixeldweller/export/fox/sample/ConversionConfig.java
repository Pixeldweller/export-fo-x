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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConversionConfig {

	/**
	 * One converter for the whole application. It holds no per-document state -- each
	 * request loads its own package -- so a single instance serves every request.
	 */
	@Bean
	public DocxToPdfConverter docxToPdfConverter(ConversionProperties properties) {

		ConversionOptions options = new ConversionOptions()
				.setNormalizeSectionBreaks(properties.isNormalizeSectionBreaks())
				.setNormalizeLineSpacing(properties.isNormalizeLineSpacing())
				.setNormalizeLeadingTabs(properties.isNormalizeLeadingTabs());

		for (String directory : properties.getFontDirectories()) {
			options.addFontDirectory(new File(directory));
		}
		return new DocxToPdfConverter(options);
	}
}

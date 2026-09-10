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

import org.docx4j.convert.out.fo.x.FontCacheHome;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Takes a DOCX by HTTP and answers with the PDF.
 *
 * <pre>
 * curl -F file=@letter.docx http://localhost:8080/api/convert -o letter.pdf
 * </pre>
 *
 * There is also a small upload form at http://localhost:8080/ .
 */
@SpringBootApplication
@EnableConfigurationProperties(ConversionProperties.class)
public class ExportFoXSampleApplication {

	public static void main(String[] args) {
		// docx4j and Apache FOP both touch java.awt for font metrics and SVG rendering.
		// Without this a server without a display can fail to start rendering at all.
		System.setProperty("java.awt.headless", "true");

		// Before Spring, and before anything can load org.docx4j.fonts: docx4j writes its
		// font cache from a static initialiser and derives the path from user.home alone.
		// A container running as an arbitrary UID has no passwd entry and therefore no
		// user.home, and the write then lands on a relative path -- or fails and takes the
		// class down with an ExceptionInInitializerError.
		FontCacheHome.ensureUsable();

		SpringApplication.run(ExportFoXSampleApplication.class, args);
	}
}

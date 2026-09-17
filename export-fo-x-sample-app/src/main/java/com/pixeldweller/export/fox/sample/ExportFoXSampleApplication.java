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

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.docx4j.convert.out.fo.x.FontCacheHome;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Takes a DOCX by HTTP and answers with the PDF.
 *
 * <pre>
 * curl -F file=@letter.docx http://localhost:8080/api/convert -o letter.pdf
 * </pre>
 *
 * There is also a form at http://localhost:8080/ , including one generated from the
 * bundled template's placeholders.
 *
 * <p>Extending {@link SpringBootServletInitializer} lets the same sources build either a
 * runnable jar or a war for an external Tomcat; see the {@code war} Maven profile.
 */
@SpringBootApplication
@EnableConfigurationProperties(ConversionProperties.class)
public class ExportFoXSampleApplication extends SpringBootServletInitializer {

	static {
		// In a static initialiser rather than in main(), because a war deployment never
		// calls main() -- the container calls SpringBootServletInitializer.onStartup, and
		// anything set up in main() is simply skipped. This block runs either way, as soon
		// as the application class is loaded.

		// docx4j and Apache FOP both reach for java.awt, for font metrics and for SVG
		// rendering. Without this a server with no display can fail outright.
		System.setProperty("java.awt.headless", "true");

		// Before anything can load org.docx4j.fonts: docx4j writes its font cache from a
		// static initialiser and derives the path from user.home alone. A container
		// running as an arbitrary UID has no passwd entry and therefore no user.home, and
		// the write then lands on a relative path -- or fails and takes the class down
		// with an ExceptionInInitializerError.
		//
		// Note that under Tomcat java.io.tmpdir is $CATALINA_BASE/temp, not /tmp, so on a
		// host where only /tmp is writable, pass -Duser.home=/tmp/... as well. See the
		// "Betrieb im Tomcat" section of README.md.
		FontCacheHome.ensureUsable();
	}

	public static void main(String[] args) {
		SpringApplication.run(ExportFoXSampleApplication.class, args);
	}

	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
		return builder.sources(ExportFoXSampleApplication.class);
	}

	/**
	 * Runs before Spring starts, and therefore before anything can load
	 * {@code org.docx4j.fonts}. The static initialiser above has already had a go at
	 * {@code user.home}, but only with the temp directories to choose from; here the
	 * servlet container's own temp directory is available, which the servlet
	 * specification requires to be writable. That matters on a host where
	 * {@code $CATALINA_BASE/temp} is read-only and no JVM options can be added.
	 *
	 * <p>If the earlier attempt already succeeded, this call does nothing.
	 */
	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		Object tempDir = servletContext.getAttribute(ServletContext.TEMPDIR);
		if (tempDir instanceof File) {
			FontCacheHome.ensureUsable((File) tempDir);
		}
		super.onStartup(servletContext);
	}
}

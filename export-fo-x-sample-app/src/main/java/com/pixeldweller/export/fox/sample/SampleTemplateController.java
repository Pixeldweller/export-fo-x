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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.pixeldweller.export.fox.sample.template.Placeholder;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The bundled template, as a fill-in-the-blanks form.
 *
 * <p>{@code GET /api/sample/fields} lists what the template asks for, each field with a
 * value that fits it, so the form is usable without typing anything.
 * {@code POST /api/sample/pdf} takes those values back and answers with the PDF.
 */
@RestController
@RequestMapping("/api/sample")
public class SampleTemplateController {

	private static final Logger log = LoggerFactory.getLogger(SampleTemplateController.class);

	private static final String TEMPLATE = "/sample/freigabe_antragsteller_auto.docx";
	private static final String PDF_NAME = "musterbescheid.pdf";

	private final ConversionService conversionService;

	public SampleTemplateController(ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	@GetMapping(path = "/fields", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Placeholder> fields() {
		try (InputStream template = openTemplate()) {
			return conversionService.placeholdersOf(template);
		} catch (IOException | Docx4JException e) {
			log.error("Could not read the bundled template", e);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"The bundled template could not be read: " + e.getMessage());
		}
	}

	@PostMapping(path = "/pdf",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_PDF_VALUE)
	public ResponseEntity<byte[]> pdf(@RequestBody(required = false) Map<String, String> values) {

		byte[] pdf;
		try (InputStream template = openTemplate()) {
			pdf = conversionService.toPdf(template, PDF_NAME,
					values == null ? Map.of() : values);
		} catch (IOException e) {
			log.error("Could not read the bundled template", e);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"The bundled template could not be read: " + e.getMessage());
		} catch (Docx4JException e) {
			log.warn("Could not convert the bundled template", e);
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"The template could not be converted: " + e.getMessage());
		}

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_PDF);
		headers.setContentDisposition(ContentDisposition.attachment()
				.filename(PDF_NAME, StandardCharsets.UTF_8).build());
		headers.setContentLength(pdf.length);
		return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
	}

	private InputStream openTemplate() throws IOException {
		InputStream in = SampleTemplateController.class.getResourceAsStream(TEMPLATE);
		if (in == null) {
			throw new IOException("Not on the classpath: " + TEMPLATE);
		}
		return in;
	}
}

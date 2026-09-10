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

/**
 * One placeholder found in a template.
 *
 * @param name         the name between the two {@code $}, eg {@code NACHNAME_ANTRAGSTELLER}
 * @param label        the same thing, made readable for a form label
 * @param defaultValue a suggested value that fits the field, so the form is usable
 *                     without typing anything
 */
public record Placeholder(String name, String label, String defaultValue) {
}

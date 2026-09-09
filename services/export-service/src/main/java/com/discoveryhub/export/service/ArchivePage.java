package com.discoveryhub.export.service;

import com.discoveryhub.contracts.Message;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The subset of a Spring Data {@code Page<Message>} that P5 needs from P2's {@code GET /messages}.
 * Unknown properties (page metadata, sort, etc.) are ignored rather than modelled — P5 only reads
 * {@code content} and page-of-content-ness.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArchivePage(List<Message> content, boolean last, int totalPages) {
}

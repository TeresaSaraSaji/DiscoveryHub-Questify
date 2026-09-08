package com.discoveryhub.ingestion.api;

import com.discoveryhub.contracts.Message;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.ValueInstantiationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns the raw JSON array of a {@code POST /messages} body into a {@link MessageBatch}, converting
 * one element at a time so a malformed message is a per-item rejection rather than a lost batch.
 */
@Component
public class MessageBatchDecoder {

    private final ObjectMapper mapper;

    public MessageBatchDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public MessageBatch decode(List<JsonNode> body) {
        List<MessageBatch.Entry> entries = new ArrayList<>(body.size());
        for (JsonNode element : body) {
            entries.add(decodeOne(element));
        }
        return new MessageBatch(entries);
    }

    private MessageBatch.Entry decodeOne(JsonNode element) {
        if (element == null || element.isNull()) {
            return MessageBatch.Entry.undecodable(null, "message must not be null");
        }
        try {
            return MessageBatch.Entry.decoded(mapper.treeToValue(element, Message.class));
        } catch (RuntimeException e) {
            // Jackson 3 exceptions are unchecked, and the contracts records reject bad input from
            // their own constructors (List.copyOf on a null element, for one). Anything thrown
            // while converting a single element is that element's problem, not the batch's.
            return MessageBatch.Entry.undecodable(externalIdOf(element), reason(e, element));
        }
    }

    private static String externalIdOf(JsonNode element) {
        // Defaults to null when the field is absent or is not a string, which is exactly the case
        // where there is no id worth reporting back.
        return element.path("externalId").stringValue(null);
    }

    private static String reason(RuntimeException e, JsonNode element) {
        if (e instanceof ValueInstantiationException) {
            // The record's own constructor refused the value, so Jackson has no field path to
            // report and the message degrades to "problem: NullPointerException". The one way a
            // well-formed element gets here is a null inside an array field, which the List.copyOf
            // calls in the contracts records reject — so name the field rather than the symptom.
            String field = fieldWithNullElement(element);
            if (field != null) {
                return "malformed field '" + field + "': array must not contain null elements";
            }
        }
        String detail = e instanceof DatabindException databind
                ? databind.getOriginalMessage()
                : e.getMessage();
        String field = e instanceof DatabindException databind ? pathOf(databind) : "";
        return field.isEmpty()
                ? "malformed message: " + detail
                : "malformed field '" + field + "': " + detail;
    }

    /** Scans whatever arrays the element actually has, so an added contract field needs no edit. */
    private static String fieldWithNullElement(JsonNode element) {
        for (Map.Entry<String, JsonNode> property : element.properties()) {
            if (!property.getValue().isArray()) {
                continue;
            }
            for (JsonNode item : property.getValue()) {
                if (item.isNull()) {
                    return property.getKey();
                }
            }
        }
        return null;
    }

    private static String pathOf(DatabindException e) {
        return e.getPath().stream()
                .map(DatabindException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
    }
}

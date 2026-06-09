package nz.co.eroad.qaisystem.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Jackson deserializer that accepts a field as either:
 * <ul>
 *   <li>A JSON array:            {@code ["a","b","c"]}</li>
 *   <li>A comma-separated string: {@code "a, b, c"}</li>
 *   <li>{@code null} → empty list</li>
 * </ul>
 *
 * <p>Trims whitespace from each element and drops blank entries.
 */
public class CommaSeparatedListDeserializer extends StdDeserializer<List<String>> {

    public CommaSeparatedListDeserializer() {
        super(List.class);
    }

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            // Already a JSON array — collect each string element
            List<String> result = new java.util.ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                String val = p.getValueAsString();
                if (val != null && !val.isBlank()) result.add(val.trim());
            }
            return List.copyOf(result);
        }

        // String (possibly comma-separated)
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank()) return List.of();

        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }
}


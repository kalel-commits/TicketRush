package com.ticketrush.assistant.tools;

import com.ticketrush.assistant.chat.ChatService;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Shared tool plumbing: caller token from the ToolContext, field trimming, errors as tool results (spec §24.5). */
final class ToolSupport {

    private ToolSupport() {
    }

    static String token(ToolContext context) {
        Object t = context == null ? null : context.getContext().get(ChatService.TOOL_CONTEXT_AUTHORIZATION);
        if (t == null) throw new ReadOnlyServiceClient.LookupFailed("unavailable");
        return t.toString();
    }

    /** Keep only the listed fields (no email, no payment provider references ever reach the model). */
    static ObjectNode pick(ObjectMapper json, JsonNode source, List<String> fields) {
        ObjectNode out = json.createObjectNode();
        for (String f : fields) {
            if (source.has(f)) out.set(f, source.get(f));
        }
        return out;
    }

    static ArrayNode pickAll(ObjectMapper json, JsonNode array, List<String> fields) {
        ArrayNode out = json.createArrayNode();
        if (array != null) array.forEach(n -> out.add(pick(json, n, fields)));
        return out;
    }

    /** Runs a lookup; failures become {"error": "..."} for the model instead of exceptions in the tool loop. */
    static String result(ObjectMapper json, Supplier<JsonNode> lookup) {
        try {
            return json.writeValueAsString(lookup.get());
        } catch (ReadOnlyServiceClient.LookupFailed e) {
            return json.writeValueAsString(Map.of("error", e.reason()));
        }
    }
}

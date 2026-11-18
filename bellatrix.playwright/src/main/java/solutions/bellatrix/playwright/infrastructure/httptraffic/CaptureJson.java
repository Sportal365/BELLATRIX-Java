package solutions.bellatrix.playwright.infrastructure.httptraffic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;

public final class CaptureJson {
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>) (json, type, context) ->
                    LocalDateTime.parse(json.getAsString().replace("+00:00", ""), LOCAL_TIME))
            .registerTypeAdapter(OffsetDateTime.class, (JsonDeserializer<OffsetDateTime>) (json, type, context) ->
                    offsetTime(json.getAsString()))
            .registerTypeAdapterFactory(new EmptyArrayAdapter())
            .create();

    private CaptureJson() {
    }

    public static <T> T request(String body, Type modelType) {
        return GSON.fromJson(body, modelType);
    }

    public static <T> T response(String body, Type modelType) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        var json = JsonParser.parseString(body);
        if (json instanceof JsonObject object && object.has("data")) {
            json = object.get("data");
        }
        return GSON.fromJson(json, modelType);
    }

    private static OffsetDateTime offsetTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value, LOCAL_TIME).atOffset(ZoneOffset.UTC);
        }
    }

    private static final class EmptyArrayAdapter implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            if (Collection.class.isAssignableFrom(type.getRawType()) || type.getRawType().isArray()) {
                return delegate;
            }
            return new TypeAdapter<>() {
                @Override
                public void write(JsonWriter writer, T value) throws IOException {
                    delegate.write(writer, value);
                }

                @Override
                public T read(JsonReader reader) throws IOException {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                        return delegate.read(reader);
                    }
                    var array = JsonParser.parseReader(reader).getAsJsonArray();
                    return delegate.fromJsonTree(array.isEmpty() ? new JsonObject() : array);
                }
            };
        }
    }
}

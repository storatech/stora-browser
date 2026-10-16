package mn.stora.headless;

import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Small JSON helpers on top of Gson. */
public final class Json {

  public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

  private Json() {
  }

  /** Builds an object from alternating key/value pairs. Null values are skipped. */
  public static JsonObject obj(Object... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("obj() needs key/value pairs");
    }
    JsonObject o = new JsonObject();
    for (int i = 0; i < keyValues.length; i += 2) {
      Object value = keyValues[i + 1];
      if (value != null) {
        o.add((String) keyValues[i], toJson(value));
      }
    }
    return o;
  }

  public static JsonElement toJson(Object value) {
    if (value == null) {
      return JsonNull.INSTANCE;
    }
    if (value instanceof JsonElement) {
      return (JsonElement) value;
    }
    if (value instanceof String) {
      return new JsonPrimitive((String) value);
    }
    if (value instanceof Number) {
      return new JsonPrimitive((Number) value);
    }
    if (value instanceof Boolean) {
      return new JsonPrimitive((Boolean) value);
    }
    if (value instanceof Map || value instanceof List) {
      return GSON.toJsonTree(value);
    }
    return GSON.toJsonTree(value);
  }

  public static JsonObject parse(String text) {
    return GSON.fromJson(text, JsonObject.class);
  }

  public static String str(JsonObject o, String key) {
    JsonElement e = o == null ? null : o.get(key);
    return e == null || e.isJsonNull() ? null : e.getAsString();
  }

  public static Integer integer(JsonObject o, String key) {
    JsonElement e = o == null ? null : o.get(key);
    return e == null || e.isJsonNull() ? null : e.getAsInt();
  }

  public static Long lng(JsonObject o, String key) {
    JsonElement e = o == null ? null : o.get(key);
    return e == null || e.isJsonNull() ? null : e.getAsLong();
  }

  public static Boolean bool(JsonObject o, String key) {
    JsonElement e = o == null ? null : o.get(key);
    return e == null || e.isJsonNull() ? null : e.getAsBoolean();
  }

  public static JsonObject object(JsonObject o, String key) {
    JsonElement e = o == null ? null : o.get(key);
    return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
  }

  public static JsonArray array(JsonObject o, String key) {
    JsonElement e = o == null ? null : o.get(key);
    return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
  }

  /** Encodes a Java string as a JavaScript string literal. */
  public static String jsString(String s) {
    return GSON.toJson(s);
  }
}

package net.xiaoyu233.fml.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.util.FieldReference;
import org.apache.commons.lang3.Range;

import javax.annotation.Nonnull;

public class ConfigEntry<T> extends Config {
    protected final Codec<T> codec;
    protected final FieldReference<T> configRef;
    protected final T defaultValue;
    protected String comment = null;

    public ConfigEntry(String name, Codec<T> codec, T defaultValue, FieldReference<T> configRef) {
        super(name);
        this.codec = codec;
        this.defaultValue = defaultValue;
        this.configRef = configRef;
    }

    public ConfigEntry(String name, FieldReference<T> configRef) {
        //noinspection unchecked
        this(name, (Codec<T>) Codec.getFromClass(configRef.getValueClass()), configRef.get(), configRef);
    }

    public ConfigEntry(String name, Codec<T> codec, FieldReference<T> configRef) {
        this(name, codec, configRef.get(), configRef);
    }

    public static <T> ConfigEntry<T> of(String name, FieldReference<T> configRef) {
        return new ConfigEntry<>(name, configRef);
    }

    public static <T extends Comparable<T>> RangedConfigEntry<T> ranged(String name, FieldReference<T> configRef, Range<T> range) {
        return new RangedConfigEntry<T>(name, configRef, range);
    }

    public static <T extends Comparable<T>> RangedConfigEntry<T> ranged(String name, Codec<T> codec, FieldReference<T> configRef, Range<T> range) {
        return new RangedConfigEntry<T>(name, codec, configRef, range);
    }

    public static <T extends Comparable<T>> RangedConfigEntry<T> ranged(String name, Codec<T> codec, T defaultValue, FieldReference<T> configRef, Range<T> range) {
        return new RangedConfigEntry<T>(name, codec, defaultValue, configRef, range);
    }

    public Codec<T> getCodec() {
        return codec;
    }

    public String getComment() {
        return comment;
    }

    public T getCurrentValue() {
        return configRef.get();
    }

    public void setCurrentValue(T value) {
        this.configRef.set(value);
    }


    public T getDefaultValue() {
        return defaultValue;
    }

    @Nonnull
    @Override
    public Config.ReadResult read(JsonElement json) {
        try {
            if (json != null) {
                if (json.isJsonObject()) {
                    this.configRef.set(this.codec.read(json.getAsJsonObject().get("value")));
                    if (!json.getAsJsonObject().get("_comment").getAsString().equals(this.comment)) {
                        return Config.ReadResult.ofChanged(this.writeWithValue(this.configRef.get()));
                    }
                } else {
                    this.configRef.set(this.codec.read(json));
                    if (this.comment != null && !this.comment.isEmpty()) {
                        return Config.ReadResult.ofChanged(this.writeWithValue(this.configRef.get()));
                    }
                }
                return Config.ReadResult.NO_CHANGE;
            } else {
                this.configRef.set(this.defaultValue);
                return Config.ReadResult.ofChanged(this.writeDefault());
            }
        } catch (Throwable t) {
            FishModLoader.LOGGER.error("Cannot read config: " + this.getName(), t);
            this.configRef.set(this.defaultValue);
            return Config.ReadResult.ofChanged(this.writeDefault());
        }
    }

    public ConfigEntry<T> withComment(String comment) {
        this.comment = comment;
        return this;
    }

    @Override
    public JsonElement writeDefault() {
        return this.writeWithValue(this.defaultValue);
    }

    @Override
    public JsonElement write() {
        return this.writeWithValue(this.configRef.get());
    }

    protected JsonElement writeWithValue(T value) {
        if (this.comment != null) {
            JsonObject json = new JsonObject();
            json.addProperty("_comment", this.comment);
            json.add("value", this.codec.write(value));
            return json;
        }
        return this.codec.write(value);
    }
}

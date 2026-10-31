package org.lwjgl.openal;

/**
 * LWJGL 2 API: utility to query EFX (effects extension) support. Nothing is cached: every call asks the live device.
 */
public final class EFXUtil {
    private static final int EFFECT = 1111;
    private static final int FILTER = 2222;

    private EFXUtil() {
    }

    /**
     * Checks whether the EFX extension (ALC_EXT_EFX) is available on the current device.
     *
     * @throws OpenALException if OpenAL has not been created
     */
    public static boolean isEfxSupported() {
        if (!AL.isCreated()) {
            throw new OpenALException("OpenAL has not been created.");
        }
        return ALC10.alcIsExtensionPresent(AL.getDevice(), "ALC_EXT_EFX");
    }

    /**
     * Checks whether an effect type is supported.
     *
     * @param effectType one of the EFX10.AL_EFFECT_* types
     * @throws IllegalArgumentException if the type is unknown
     */
    public static boolean isEffectSupported(final int effectType) {
        switch (effectType) {
            case EFX10.AL_EFFECT_NULL:
            case EFX10.AL_EFFECT_EAXREVERB:
            case EFX10.AL_EFFECT_REVERB:
            case EFX10.AL_EFFECT_CHORUS:
            case EFX10.AL_EFFECT_DISTORTION:
            case EFX10.AL_EFFECT_ECHO:
            case EFX10.AL_EFFECT_FLANGER:
            case EFX10.AL_EFFECT_FREQUENCY_SHIFTER:
            case EFX10.AL_EFFECT_VOCAL_MORPHER:
            case EFX10.AL_EFFECT_PITCH_SHIFTER:
            case EFX10.AL_EFFECT_RING_MODULATOR:
            case EFX10.AL_EFFECT_AUTOWAH:
            case EFX10.AL_EFFECT_COMPRESSOR:
            case EFX10.AL_EFFECT_EQUALIZER:
                break;
            default:
                throw new IllegalArgumentException("Unknown or invalid effect type: " + effectType);
        }
        return testSupportGeneric(EFFECT, effectType);
    }

    /**
     * Checks whether a filter type is supported.
     *
     * @param filterType one of the EFX10.AL_FILTER_* types
     * @throws IllegalArgumentException if the type is unknown
     */
    public static boolean isFilterSupported(final int filterType) {
        switch (filterType) {
            case EFX10.AL_FILTER_NULL:
            case EFX10.AL_FILTER_LOWPASS:
            case EFX10.AL_FILTER_HIGHPASS:
            case EFX10.AL_FILTER_BANDPASS:
                break;
            default:
                throw new IllegalArgumentException("Unknown or invalid filter type: " + filterType);
        }
        return testSupportGeneric(FILTER, filterType);
    }

    /** Creates a temporary effect/filter object and tries to give it the type. */
    private static boolean testSupportGeneric(final int objectType, final int typeValue) {
        if (objectType != EFFECT && objectType != FILTER) {
            throw new IllegalArgumentException("Invalid objectType: " + objectType);
        }
        boolean supported = false;
        if (isEfxSupported()) {
            AL10.alGetError();

            int genError;
            int object = 0;
            try {
                object = objectType == EFFECT ? EFX10.alGenEffects() : EFX10.alGenFilters();
                genError = AL10.alGetError();
            } catch (final OpenALException | UnsupportedOperationException e) {
                if (String.valueOf(e.getMessage()).contains("AL_OUT_OF_MEMORY")) {
                    genError = AL10.AL_OUT_OF_MEMORY;
                } else {
                    genError = AL10.AL_INVALID_OPERATION;
                }
            }
            if (genError == AL10.AL_NO_ERROR) {
                AL10.alGetError();

                int setError;
                try {
                    if (objectType == EFFECT) {
                        EFX10.alEffecti(object, EFX10.AL_EFFECT_TYPE, typeValue);
                    } else {
                        EFX10.alFilteri(object, EFX10.AL_FILTER_TYPE, typeValue);
                    }
                    setError = AL10.alGetError();
                } catch (final OpenALException e) {
                    setError = AL10.AL_INVALID_VALUE;
                }
                if (setError == AL10.AL_NO_ERROR) {
                    supported = true;
                }

                try {
                    if (objectType == EFFECT) {
                        EFX10.alDeleteEffects(object);
                    } else {
                        EFX10.alDeleteFilters(object);
                    }
                } catch (final OpenALException ignored) {
                }
            } else if (genError == AL10.AL_OUT_OF_MEMORY) {
                throw new OpenALException(genError);
            }
        }
        return supported;
    }
}

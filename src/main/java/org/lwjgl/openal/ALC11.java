package org.lwjgl.openal;

import java.nio.ByteBuffer;
import net.xiaoyu233.fml.vulkan.audio.OpenALCore;

/**
 * LWJGL 2 API: OpenAL Context API 1.1 (capture devices).
 *
 * <p>Hand-written (it registers capture devices in {@code ALC10.devices}); only the block between the generated-constants
 * markers is maintained by {@code tools/GenLwjgl2OpenAL.java}. The FFM calls are in {@link OpenALCore}.
 */
public final class ALC11 {
    // <generated-constants>
    public static final int ALC_DEFAULT_ALL_DEVICES_SPECIFIER = 0x1012;
    public static final int ALC_ALL_DEVICES_SPECIFIER = 0x1013;
    public static final int ALC_CAPTURE_DEVICE_SPECIFIER = 0x310;
    public static final int ALC_CAPTURE_DEFAULT_DEVICE_SPECIFIER = 0x311;
    public static final int ALC_CAPTURE_SAMPLES = 0x312;
    public static final int ALC_MONO_SOURCES = 0x1010;
    public static final int ALC_STEREO_SOURCES = 0x1011;
    // </generated-constants>

    public ALC11() {
    }

    /**
     * @param devicename the capture device specifier, null opens the default capture device
     * @return the capture device, or null if it could not be opened
     */
    public static ALCdevice alcCaptureOpenDevice(String devicename, int frequency, int format, int buffersize) {
        long device_address = OpenALCore.alcCaptureOpenDevice(devicename, frequency, format, buffersize);
        if (device_address != 0) {
            ALCdevice device = new ALCdevice(device_address);
            synchronized (ALC10.devices) {
                ALC10.devices.put(device_address, device);
            }
            return device;
        }
        return null;
    }

    private static long nalcCaptureOpenDevice(long devicename, int frequency, int format, int buffersize) {
        return OpenALCore.nalcCaptureOpenDevice(devicename, frequency, format, buffersize);
    }

    public static boolean alcCaptureCloseDevice(ALCdevice device) {
        boolean result = nalcCaptureCloseDevice(ALC10.getDevice(device));
        synchronized (ALC10.devices) {
            device.setInvalid();
            ALC10.devices.remove(device.device);
        }
        return result;
    }

    static boolean nalcCaptureCloseDevice(long device) {
        return OpenALCore.nalcCaptureCloseDevice(device);
    }

    public static void alcCaptureStart(ALCdevice device) {
        nalcCaptureStart(ALC10.getDevice(device));
    }

    static void nalcCaptureStart(long device) {
        OpenALCore.nalcCaptureStart(device);
    }

    public static void alcCaptureStop(ALCdevice device) {
        nalcCaptureStop(ALC10.getDevice(device));
    }

    static void nalcCaptureStop(long device) {
        OpenALCore.nalcCaptureStop(device);
    }

    public static void alcCaptureSamples(ALCdevice device, ByteBuffer buffer, int samples) {
        OpenALCore.alcCaptureSamples(ALC10.getDevice(device), buffer, samples);
    }

    static void nalcCaptureSamples(long device, long buffer, int samples) {
        OpenALCore.nalcCaptureSamples(device, buffer, samples);
    }
}

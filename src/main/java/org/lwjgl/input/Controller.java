package org.lwjgl.input;

/**
 * LWJGL 2 API: a game controller (interface of LWJGL 2.9.0). Owned by module E. The Vulkan port reports no controllers
 * (see {@link Controllers}).
 */
public interface Controller {
    String getName();

    int getIndex();

    int getButtonCount();

    String getButtonName(int index);

    boolean isButtonPressed(int index);

    void poll();

    float getPovX();

    float getPovY();

    float getDeadZone(int index);

    void setDeadZone(int index, float zone);

    int getAxisCount();

    String getAxisName(int index);

    float getAxisValue(int index);

    float getXAxisValue();

    float getXAxisDeadZone();

    void setXAxisDeadZone(float zone);

    float getYAxisValue();

    float getYAxisDeadZone();

    void setYAxisDeadZone(float zone);

    float getZAxisValue();

    float getZAxisDeadZone();

    void setZAxisDeadZone(float zone);

    float getRXAxisValue();

    float getRXAxisDeadZone();

    void setRXAxisDeadZone(float zone);

    float getRYAxisValue();

    float getRYAxisDeadZone();

    void setRYAxisDeadZone(float zone);

    float getRZAxisValue();

    float getRZAxisDeadZone();

    void setRZAxisDeadZone(float zone);

    int getRumblerCount();

    String getRumblerName(int index);

    void setRumblerStrength(int index, float strength);
}

package net.xiaoyu233.fml.reload.event;

import net.xiaoyu233.fml.reload.utils.IdUtil;

public class PacketRegisterEvent {
    private final RegisterFunction registerer;

    public PacketRegisterEvent(RegisterFunction registerer) {
        this.registerer = registerer;
    }

    @Deprecated(since = "4.0.0")
    public void register(boolean clientProcess, boolean serverProcess, Class<?> packetClass) {
        this.registerer.register(IdUtil.getNextPacketID(), clientProcess, serverProcess, packetClass);
    }

    public void register(int id, boolean clientProcess, boolean serverProcess, Class<?> packetClass) {
        this.registerer.register(id, clientProcess, serverProcess, packetClass);
    }

    @FunctionalInterface
    public interface RegisterFunction {
        void register(int id, boolean clientProcess, boolean serverProcess, Class<?> packetClass);
    }
}

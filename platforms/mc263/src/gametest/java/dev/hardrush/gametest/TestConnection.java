package dev.hardrush.gametest;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;

/**
 * The server end of a fixture client's network connection: everything the server sends is recorded, in order,
 * the way the vanilla client would decode it, with bundles unpacked into their sub-packets.
 *
 * <p>Chunk and light payloads are not kept. A fixture client that confirms its chunk batches streams its whole view
 * distance, and keeping several hundred chunk payloads per client only costs memory; the batch boundaries that
 * {@link TestClient} confirms are still recorded.
 *
 * <p>A send that carries a completion listener completes at once, as a socket would once the packet was written:
 * vanilla closes a kicked player's connection from the listener of its disconnect packet.
 *
 * <p>Tests normally reach this through {@link TestClient}. Use it directly only for a connection the test builds by
 * hand on purpose, for example to swap a connection halfway through an exceptional path.
 */
public final class TestConnection extends Connection {
    private final List<Packet<?>> packets = Collections.synchronizedList(new ArrayList<>());
    private final Consumer<Packet<?>> observer;
    private Channel channel;

    public TestConnection() { this(packet -> {}); }

    /** {@code observer} sees every unbundled packet as it arrives, including the chunk payloads that are not kept. */
    TestConnection(Consumer<Packet<?>> observer) {
        super(PacketFlow.SERVERBOUND);
        this.observer = observer;
    }

    /** A copy of everything received since the connection opened or was last {@linkplain #clear() cleared}. */
    public List<Packet<?>> packets() {
        synchronized (packets) { return List.copyOf(packets); }
    }

    /** The received packets of one type, in order. */
    public <T extends Packet<?>> List<T> packets(Class<T> type) {
        synchronized (packets) { return packets.stream().filter(type::isInstance).map(type::cast).toList(); }
    }

    public int size() { return packets.size(); }

    public void clear() { packets.clear(); }

    private void record(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record);
        else {
            observer.accept(packet);
            if (!(packet instanceof ClientboundLevelChunkWithLightPacket) && !(packet instanceof ClientboundLightUpdatePacket)) packets.add(packet);
        }
    }

    @Override public void channelActive(ChannelHandlerContext context) throws Exception {
        super.channelActive(context);
        channel = context.channel();
    }

    @Override public void send(Packet<?> packet) { record(packet); }
    @Override public void send(Packet<?> packet, ChannelFutureListener listener) { send(packet, listener, true); }
    @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        record(packet);
        if (listener == null || channel == null) return;
        try { listener.operationComplete(channel.newSucceededFuture()); }
        catch (Exception error) { throw new IllegalStateException("Send listener of " + packet.type() + " failed", error); }
    }
}

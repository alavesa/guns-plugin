package fi.alavesa.guns;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

/**
 * Teleport-free camera recoil: sends a {@code ClientboundPlayerPositionPacket} that only nudges
 * the view rotation (relative X_ROT / Y_ROT) with a zero position delta, so the client rotates the
 * camera without the server moving the player at all - no teleport, no events, no rubber-band.
 *
 * All NMS is reached by reflection so the plugin still compiles against paper-api; if any class or
 * member is missing on the running server it disables itself and callers fall back to a teleport.
 */
public final class NmsRecoil {

    private static boolean ok = false;
    private static Constructor<?> pmrCtor;   // PositionMoveRotation(Vec3, Vec3, float yRot, float xRot)
    private static Constructor<?> pktCtor;   // ClientboundPlayerPositionPacket(int, PositionMoveRotation, Set)
    private static Object vec3Zero;
    private static Object relativesSet;      // EnumSet {X, Y, Z, X_ROT, Y_ROT}
    private static Class<?> packetClass;
    private static Method getHandle;
    private static Field connectionField;
    private static Method sendMethod;

    private NmsRecoil() { }

    static {
        try {
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            vec3Zero = vec3.getField("ZERO").get(null);
            Class<?> pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            pmrCtor = pmr.getConstructor(vec3, vec3, float.class, float.class);
            Class<?> rel = Class.forName("net.minecraft.world.entity.Relative");
            @SuppressWarnings({"unchecked", "rawtypes"})
            EnumSet set = EnumSet.noneOf((Class) rel);
            for (String n : new String[]{"X", "Y", "Z", "X_ROT", "Y_ROT"}) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object c = Enum.valueOf((Class) rel, n);
                set.add(c);
            }
            relativesSet = set;
            packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket");
            pktCtor = packetClass.getConstructor(int.class, pmr, Set.class);
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
    }

    public static boolean available() { return ok; }

    /** Add {@code yawDelta} / {@code pitchDelta} degrees to the player's view via the position
     *  packet (relative rotation only). Returns false if it couldn't send (caller should fall back). */
    public static boolean sendRotation(Player player, float yawDelta, float pitchDelta) {
        if (!ok) return false;
        try {
            Object handle = getHandle(player);
            Object connection = connection(handle);
            Object pmr = pmrCtor.newInstance(vec3Zero, vec3Zero, yawDelta, pitchDelta);
            Object packet = pktCtor.newInstance(0, pmr, relativesSet);
            send(connection, packet);
            return true;
        } catch (Throwable t) {
            ok = false;   // stop trying; caller falls back to teleport
            return false;
        }
    }

    private static Object getHandle(Player p) throws Exception {
        if (getHandle == null) getHandle = p.getClass().getMethod("getHandle");
        return getHandle.invoke(p);
    }

    private static Object connection(Object handle) throws Exception {
        if (connectionField == null) {
            connectionField = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
        }
        return connectionField.get(handle);
    }

    private static void send(Object connection, Object packet) throws Exception {
        if (sendMethod == null) {
            Class<?> base = Class.forName("net.minecraft.network.protocol.Packet");
            for (Class<?> c = connection.getClass(); c != null && sendMethod == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("send") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(base)) {
                        m.setAccessible(true);
                        sendMethod = m;
                        break;
                    }
                }
            }
        }
        sendMethod.invoke(connection, packet);
    }
}

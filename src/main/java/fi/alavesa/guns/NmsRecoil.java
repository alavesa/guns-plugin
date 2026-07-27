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
    private static boolean fovOk = false;
    private static Constructor<?> pmrCtor;   // PositionMoveRotation(Vec3, Vec3, float yRot, float xRot)
    private static Constructor<?> pktCtor;   // ClientboundPlayerPositionPacket(int, PositionMoveRotation, Set)
    private static Constructor<?> vec3Ctor;  // Vec3(double, double, double)
    private static Constructor<?> motionCtor;// ClientboundSetEntityMotionPacket(int, Vec3)
    private static Object vec3Zero;
    private static Object relativesSet;      // EnumSet {X, Y, Z, X_ROT, Y_ROT}
    private static Constructor<?> explodeCtor;   // ClientboundExplodePacket(Vec3,float,int,Optional,ParticleOptions,Holder,WeightedList)
    private static Object explosionParticle;     // ParticleTypes.EXPLOSION
    private static Object silentSoundHolder;     // Holder.direct(SoundEvents.EMPTY)
    private static Object emptyWeightedList;     // WeightedList.of()
    private static Class<?> packetClass;
    private static Method getHandle;
    private static Field connectionField;
    private static Method sendMethod;

    private NmsRecoil() { }

    static {
        try {
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            vec3Zero = vec3.getField("ZERO").get(null);
            vec3Ctor = vec3.getConstructor(double.class, double.class, double.class);
            Class<?> motion = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket");
            motionCtor = motion.getConstructor(int.class, vec3);
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
        // Micro-explosion FOV kick (GunColony-style): a client-only explosion packet whose
        // playerKnockback is ADDED to the player's velocity (so it never overrides walking), with a
        // SILENT sound and a tiny particle. Reflected separately so a failure doesn't disable the pan.
        try {
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            Class<?> particleOptions = Class.forName("net.minecraft.core.particles.ParticleOptions");
            Class<?> holderCls = Class.forName("net.minecraft.core.Holder");
            Class<?> weightedListCls = Class.forName("net.minecraft.util.random.WeightedList");
            explosionParticle = Class.forName("net.minecraft.core.particles.ParticleTypes").getField("EXPLOSION").get(null);
            Object emptySound = Class.forName("net.minecraft.sounds.SoundEvents").getField("EMPTY").get(null);
            silentSoundHolder = holderCls.getMethod("direct", Object.class).invoke(null, emptySound);
            emptyWeightedList = weightedListCls.getMethod("of").invoke(null);
            explodeCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundExplodePacket")
                .getConstructor(vec3, float.class, int.class, java.util.Optional.class,
                    particleOptions, holderCls, weightedListCls);
            fovOk = true;
        } catch (Throwable t) {
            fovOk = false;
        }
    }

    public static boolean available() { return ok; }
    public static boolean fovAvailable() { return fovOk; }

    /** Send a client-only MICRO EXPLOSION at (cx,cy,cz) whose knockback (kx,ky,kz) is ADDED to the
     *  player's velocity - a subtle camera jolt (the GunColony "FOV" kick) with no walk-override and
     *  no server-side speed change. Silent, tiny particle. */
    public static boolean sendMicroExplosion(Player player, double cx, double cy, double cz,
                                             double kx, double ky, double kz) {
        if (!fovOk) return false;
        try {
            Object connection = connection(getHandle(player));
            Object center = vec3Ctor.newInstance(cx, cy, cz);
            Object kb = vec3Ctor.newInstance(kx, ky, kz);
            Object packet = explodeCtor.newInstance(center, 0.0f, 0, java.util.Optional.of(kb),
                explosionParticle, silentSoundHolder, emptyWeightedList);
            send(connection, packet);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

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

    /** Push the player's own client entity with a one-off velocity (the recoil "kick") - a motion
     *  packet, so the camera lurches without the server changing the player's movement speed. */
    public static boolean sendMotion(Player player, double x, double y, double z) {
        if (!ok) return false;
        try {
            Object connection = connection(getHandle(player));
            Object vec = vec3Ctor.newInstance(x, y, z);
            Object packet = motionCtor.newInstance(player.getEntityId(), vec);
            send(connection, packet);
            return true;
        } catch (Throwable t) {
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

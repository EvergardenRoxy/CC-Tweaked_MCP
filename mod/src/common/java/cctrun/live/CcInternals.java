// SPDX-License-Identifier: MPL-2.0
package cctrun.live;
import java.lang.reflect.Field;

/** Pinned internal fields are optional capabilities; never throw from a static initializer. */
final class CcInternals {
    static final class MissingCapability extends IllegalStateException { MissingCapability(String message) { super(message); } }
    private CcInternals() {}
    static Field optionalField(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.getLogger("cct-live").log(System.Logger.Level.WARNING, "CC:T 1.120.2 capability unavailable: " + owner.getName() + "." + name);
            return null;
        }
    }
    static Field optionalField(String owner, String name) {
        try { return optionalField(Class.forName(owner, false, CcInternals.class.getClassLoader()), name); }
        catch (ClassNotFoundException | LinkageError e) { return null; }
    }
    static Field optionalField(Class<?> owner,String name,Class<?> expected) {
        var field=optionalField(owner,name);
        return field!=null && expected.isAssignableFrom(field.getType())?field:null;
    }
}

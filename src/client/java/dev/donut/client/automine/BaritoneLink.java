package dev.donut.client.automine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Talks to Baritone through reflection, so Donut Client needs no Baritone jar to compile or to start. Only
 * public API methods that take plain Java types are used (getPrimaryBaritone, getMineProcess, mineByName,
 * isActive, cancel, cancelEverything), so Minecraft's runtime class names never matter.
 */
final class BaritoneLink {
    private static Method getProvider, getPrimary, getMineProcess, mineByName, isActive, cancelMine;
    private static Method getPathing, cancelEverything;

    private BaritoneLink() { }

    private static synchronized void bind() throws ReflectiveOperationException {
        if (mineByName != null) return;
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Class<?> provider = Class.forName("baritone.api.IBaritoneProvider");
        Class<?> baritone = Class.forName("baritone.api.IBaritone");
        Class<?> mine = Class.forName("baritone.api.process.IMineProcess");
        Class<?> pathing = Class.forName("baritone.api.behavior.IPathingBehavior");
        getProvider = api.getMethod("getProvider");
        getPrimary = provider.getMethod("getPrimaryBaritone");
        getMineProcess = baritone.getMethod("getMineProcess");
        getPathing = baritone.getMethod("getPathingBehavior");
        isActive = mine.getMethod("isActive");
        cancelMine = mine.getMethod("cancel");
        cancelEverything = pathing.getMethod("cancelEverything");
        mineByName = mine.getMethod("mineByName", int.class, String[].class);
    }

    private static Object baritone() throws ReflectiveOperationException {
        bind();
        return getPrimary.invoke(getProvider.invoke(null));
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Baritone: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Baritone API mismatch: " + e, e);
        }
    }

    /** quantity 0 means "until stopped". */
    static void mine(int quantity, List<String> names) {
        try {
            Object process = call(getMineProcessMethod(), baritone());
            call(mineByName, process, quantity, (Object) names.toArray(new String[0]));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Baritone API not found: " + e, e);
        }
    }

    static boolean active() {
        try {
            return (Boolean) call(isActive, call(getMineProcessMethod(), baritone()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Baritone API not found: " + e, e);
        }
    }

    static void cancel() {
        try {
            Object baritone = baritone();
            call(cancelMine, call(getMineProcess, baritone));
            call(cancelEverything, call(getPathing, baritone));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Baritone API not found: " + e, e);
        }
    }

    private static Method getMineProcessMethod() throws ReflectiveOperationException {
        bind();
        return getMineProcess;
    }
}

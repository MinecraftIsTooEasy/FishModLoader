package net.minecraftforge.event;

import com.google.common.reflect.TypeToken;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EventBus {
    private final ConcurrentHashMap<Object, ArrayList<HandlerEntry>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<? extends Event>, ArrayList<HandlerEntry>> listenersByEvent = new ConcurrentHashMap<>();

    public void register(Object target) {
        if (listeners.containsKey(target)) {
            return;
        }

        Set<? extends Class<?>> supers = TypeToken.of(target.getClass()).getTypes().rawTypes();
        for (Method method : target.getClass().getMethods()) {
            for (Class<?> cls : supers) {
                try {
                    Method real = cls.getDeclaredMethod(method.getName(), method.getParameterTypes());
                    if (real.isAnnotationPresent(ForgeSubscribe.class)) {
                        Class<?>[] parameterTypes = method.getParameterTypes();
                        if (parameterTypes.length != 1) {
                            throw new IllegalArgumentException(
                                    "Method " + method + " has @ForgeSubscribe annotation, but requires "
                                            + parameterTypes.length + " arguments. Event handler methods must require a single argument."
                            );
                        }

                        Class<?> eventType = parameterTypes[0];

                        if (!Event.class.isAssignableFrom(eventType)) {
                            throw new IllegalArgumentException(
                                    "Method " + method + " has @ForgeSubscribe annotation, but takes an argument that is not an Event "
                                            + eventType
                            );
                        }

                        register((Class<? extends Event>) eventType, target, method);
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
    }

    private void register(Class<? extends Event> eventType, Object target, Method method) {
        try {
            HandlerEntry entry = new HandlerEntry(eventType, new ASMEventHandler(target, method));
            ArrayList<HandlerEntry> targetListeners = listeners.get(target);
            if (targetListeners == null) {
                targetListeners = new ArrayList<>();
                listeners.put(target, targetListeners);
            }
            targetListeners.add(entry);

            ArrayList<HandlerEntry> eventListeners = listenersByEvent.get(eventType);
            if (eventListeners == null) {
                eventListeners = new ArrayList<>();
                listenersByEvent.put(eventType, eventListeners);
            }
            insertByPriority(eventListeners, entry);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void insertByPriority(ArrayList<HandlerEntry> entries, HandlerEntry entry) {
        int index = 0;
        while (index < entries.size()
                && entries.get(index).listener.getPriority().ordinal() <= entry.listener.getPriority().ordinal()) {
            index++;
        }
        entries.add(index, entry);
    }

    public void unregister(Object object) {
        ArrayList<HandlerEntry> removed = listeners.remove(object);
        if (removed == null) {
            return;
        }

        for (HandlerEntry entry : removed) {
            ArrayList<HandlerEntry> eventListeners = listenersByEvent.get(entry.eventType);
            if (eventListeners != null) {
                eventListeners.remove(entry);
            }
        }
    }

    public boolean post(Event event) {
        for (HandlerEntry entry : getListeners(event.getClass())) {
            entry.listener.invoke(event);
        }
        return event.isCancelable() && event.isCanceled();
    }

    private List<HandlerEntry> getListeners(Class<?> eventType) {
        ArrayList<HandlerEntry> result = new ArrayList<>();
        Class<?> current = eventType;
        while (current != null && Event.class.isAssignableFrom(current)) {
            ArrayList<HandlerEntry> entries = listenersByEvent.get(current);
            if (entries != null) {
                result.addAll(entries);
            }
            if (current == Event.class) {
                break;
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private static final class HandlerEntry {
        final Class<? extends Event> eventType;
        final ASMEventHandler listener;

        HandlerEntry(Class<? extends Event> eventType, ASMEventHandler listener) {
            this.eventType = eventType;
            this.listener = listener;
        }
    }
}

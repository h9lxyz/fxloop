package dev.fxloop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import dev.fxloop.protocol.Paging;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javafx.beans.property.Property;
import javafx.beans.value.ObservableValue;
import javafx.css.Styleable;
import javafx.css.StyleableProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

/** Reflective JavaFX property access with coercion (PRD 01 §7, PRD 02 §9). FX thread only. */
final class Props {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,63}");

    private Props() {}

    record Access(Object owner, String name, ObservableValue<?> observable, Method getter, Method setter) {
        Object value() {
            try {
                if (observable != null) {
                    return observable.getValue();
                }
                return getter.invoke(owner);
            } catch (ReflectiveOperationException e) {
                throw new FcpException(Fcp.Error.INTERNAL, "cannot read " + name + ": " + e.getCause());
            }
        }

        boolean bound() {
            return observable instanceof Property<?> p && p.isBound();
        }

        Class<?> type() {
            if (setter != null) {
                return setter.getParameterTypes()[0];
            }
            if (getter != null) {
                return getter.getReturnType();
            }
            Object v = value();
            return v == null ? Object.class : v.getClass();
        }
    }

    static Access access(Object owner, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new FcpException(Fcp.Error.BAD_REQUEST, "invalid property name '" + name + "'");
        }
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method propMethod = publicMethod(owner.getClass(), name + "Property");
        Method getter = publicMethod(owner.getClass(), "get" + cap);
        if (getter == null) {
            getter = publicMethod(owner.getClass(), "is" + cap);
        }
        Method setter = null;
        if (getter != null) {
            setter = publicMethod(owner.getClass(), "set" + cap, getter.getReturnType());
        }
        ObservableValue<?> obs = null;
        if (propMethod != null && ObservableValue.class.isAssignableFrom(propMethod.getReturnType())) {
            try {
                obs = (ObservableValue<?>) propMethod.invoke(owner);
            } catch (ReflectiveOperationException e) {
                throw new FcpException(Fcp.Error.INTERNAL, "cannot access " + name + "Property(): " + e.getCause());
            }
        }
        if (obs == null && getter == null) {
            throw new FcpException(Fcp.Error.PROPERTY_NOT_FOUND, NodePath.typeName0(owner) + " has no property '" + name
                    + "'; list properties with fx_get_node_details");
        }
        return new Access(owner, name, obs, getter, setter);
    }

    /** Finds a public method callable from outside the (possibly non-exported) declaring class. */
    static Method publicMethod(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            try {
                Method m = c.getMethod(name, params);
                if (Modifier.isPublic(m.getDeclaringClass().getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // try superclass
            }
        }
        return null;
    }

    static ObjectNode get(ObjectNode p) {
        return Fx.call(() -> {
            Node node = Targets.resolve(p);
            Access a = access(node, p.path("name").asText());
            return describe(a, node);
        });
    }

    static ObjectNode describe(Access a, Node node) {
        ObjectNode o = Json.object();
        o.put("name", a.name());
        NodeInfo.putValue(o, "value", a.value());
        o.put("type", a.type().getSimpleName());
        o.put("bound", a.bound());
        o.put("writable", a.setter() != null || a.observable() instanceof javafx.beans.value.WritableValue);
        if (node != null) {
            o.put("path", NodePath.of(node));
        }
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ObjectNode set(ObjectNode p) {
        return Fx.call(() -> {
            Node node = Targets.resolve(p);
            String name = p.path("name").asText();
            Access a = access(node, name);
            if (a.bound()) {
                ObjectNode data = Json.object().put("path", NodePath.of(node)).put("name", name);
                throw new FcpException(Fcp.Error.PROPERTY_BOUND, name + " of " + NodePath.of(node)
                        + " is bound — inspect the binding (fx_explain_property) and write to its source", data);
            }
            Object value = coerce(p.get("value"), a.type());
            ObjectNode out = Json.object();
            NodeInfo.putValue(out, "valueBefore", a.value());
            try {
                if (a.setter() != null) {
                    a.setter().invoke(node, value);
                } else if (a.observable() instanceof javafx.beans.value.WritableValue w) {
                    w.setValue(value);
                } else {
                    throw new FcpException(Fcp.Error.UNSUPPORTED_TARGET, name + " is read-only");
                }
            } catch (ReflectiveOperationException e) {
                throw new FcpException(Fcp.Error.COERCION_FAILED, "setting " + name + " failed: " + e.getCause());
            }
            out.put("set", true);
            out.put("name", name);
            out.put("path", NodePath.of(node));
            NodeInfo.putValue(out, "valueAfter", a.value());
            return out;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object coerce(JsonNode v, Class<?> type) {
        if (v == null || v.isNull()) {
            if (type.isPrimitive()) {
                throw new FcpException(Fcp.Error.COERCION_FAILED, "null is not valid for " + type.getSimpleName());
            }
            return null;
        }
        String s = v.asText();
        try {
            if (type == boolean.class || type == Boolean.class) {
                if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("expected true/false");
                }
                return Boolean.parseBoolean(s);
            }
            if (type == int.class || type == Integer.class) return Integer.parseInt(s.trim());
            if (type == long.class || type == Long.class) return Long.parseLong(s.trim());
            if (type == double.class || type == Double.class) return Double.parseDouble(s.trim());
            if (type == float.class || type == Float.class) return Float.parseFloat(s.trim());
            if (type == String.class || type == Object.class) return s;
            if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, s.trim().toUpperCase(Locale.ROOT));
            if (type == Color.class || type == Paint.class) return Color.web(s.trim());
            if (type == Insets.class) {
                String[] parts = s.trim().split("[ ,]+");
                double[] d = new double[parts.length];
                for (int i = 0; i < parts.length; i++) d[i] = Double.parseDouble(parts[i]);
                return parts.length == 1 ? new Insets(d[0]) : new Insets(d[0], d[1], d[2], d[3]);
            }
        } catch (RuntimeException e) {
            throw new FcpException(Fcp.Error.COERCION_FAILED, "cannot convert '" + s + "' to " + type.getSimpleName()
                    + ": " + e.getMessage());
        }
        throw new FcpException(Fcp.Error.COERCION_FAILED, "values of type " + type.getSimpleName()
                + " cannot be set from JSON (supported: boolean, numbers, String, enums, Color/Paint, Insets)");
    }

    /** {@code fx_get_node_details}: every readable property, one-line values, paginated. */
    static ObjectNode details(ObjectNode p) {
        return Fx.call(() -> {
            Node node = Targets.resolve(p);
            TreeMap<String, ObjectNode> props = new TreeMap<>();
            java.util.Set<String> styleable = new java.util.HashSet<>();
            {
                Styleable s = node;
                for (var meta : s.getCssMetaData()) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    StyleableProperty<?> sp = ((javafx.css.CssMetaData) meta).getStyleableProperty(s);
                    if (sp instanceof javafx.beans.property.ReadOnlyProperty) {
                        styleable.add(((javafx.beans.property.ReadOnlyProperty<?>) sp).getName());
                    }
                }
            }
            for (Method m : node.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().endsWith("Property") && !Modifier.isStatic(m.getModifiers())
                        && ObservableValue.class.isAssignableFrom(m.getReturnType())) {
                    String name = m.getName().substring(0, m.getName().length() - "Property".length());
                    if (props.containsKey(name)) {
                        continue;
                    }
                    try {
                        Access a = access(node, name);
                        ObjectNode o = describe(a, null);
                        o.put("styleable", styleable.contains(name));
                        props.put(name, o);
                    } catch (RuntimeException ignored) {
                        // inaccessible property: skip
                    }
                }
            }
            List<JsonNode> items = new ArrayList<>(props.values());
            ObjectNode page = Paging.page(items, p.path("offset").asInt(0), Math.min(200, p.path("limit").asInt(60)));
            page.set("target", Targets.summary(node));
            page.put("class", node.getClass().getName());
            return page;
        });
    }
}

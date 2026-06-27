package com.zzhalex233.blockxray.compat;

import com.zzhalex233.blockxray.BlockXray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CodeChickenLibCompat {

    private static final String REFLECTION_MANAGER = "codechicken.lib.reflect.ReflectionManager";

    private CodeChickenLibCompat() {
    }

    public static void apply() {
        try {
            Class<?> reflectionManager = Class.forName(REFLECTION_MANAGER, false, CodeChickenLibCompat.class.getClassLoader());
            Field cclModifiersField = reflectionManager.getDeclaredField("modifiersField");
            cclModifiersField.setAccessible(true);
            if (cclModifiersField.get(null) != null) {
                return;
            }

            Field modifiersField = findFieldModifiers();
            if (modifiersField == null) {
                BlockXray.LOGGER.warn("CodeChickenLib compatibility skipped: java.lang.reflect.Field.modifiers was not found.");
                return;
            }

            modifiersField.setAccessible(true);
            cclModifiersField.set(null, modifiersField);
            BlockXray.LOGGER.info("Applied CodeChickenLib reflection compatibility for modern Java.");
        } catch (ClassNotFoundException ignored) {
            // CodeChickenLib is optional.
        } catch (Throwable throwable) {
            BlockXray.LOGGER.warn("CodeChickenLib compatibility failed.", throwable);
        }
    }

    private static Field findFieldModifiers() throws Exception {
        try {
            return Field.class.getDeclaredField("modifiers");
        } catch (NoSuchFieldException ignored) {
            Method getDeclaredFields0 = Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class);
            getDeclaredFields0.setAccessible(true);
            Field[] fields = (Field[]) getDeclaredFields0.invoke(Field.class, false);
            for (Field field : fields) {
                if ("modifiers".equals(field.getName())) {
                    return field;
                }
            }
            return null;
        }
    }
}

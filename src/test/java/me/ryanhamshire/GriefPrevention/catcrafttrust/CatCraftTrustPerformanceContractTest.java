package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatCraftTrustPerformanceContractTest
{
    private static final Set<Class<?>> VALUE_TYPES = Set.of(
            String.class,
            UUID.class,
            CatCraftTrustKind.class,
            TrustDimension.class,
            NativeTrustState.class);

    @Test
    void persistedTrustRecordsRetainOnlyPrimitiveAndSmallValueTypes()
    {
        assertSmallValueFields(TemporaryTrustRecord.class);
        assertSmallValueFields(NativeTrustState.class);
    }

    @Test
    void readOnlyViewSessionsRetainNoBukkitObjects()
    {
        Class<?> session = Arrays.stream(ReadOnlyContainerListener.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("ViewSession"))
                .findFirst()
                .orElseThrow();

        for (Field field : session.getDeclaredFields())
        {
            assertFalse(field.getType().getName().startsWith("org.bukkit."),
                    () -> field + " must not retain a Bukkit object");
            assertTrue(field.getType().isPrimitive() || field.getType() == UUID.class,
                    () -> field + " must remain a primitive or UUID");
        }
    }

    private static void assertSmallValueFields(Class<?> type)
    {
        for (Field field : type.getDeclaredFields())
        {
            Class<?> fieldType = field.getType();
            assertFalse(fieldType.getName().startsWith("org.bukkit."),
                    () -> field + " must not retain a Bukkit object");
            assertTrue(fieldType.isPrimitive() || fieldType.isEnum() || VALUE_TYPES.contains(fieldType),
                    () -> field + " must remain a primitive or small immutable value");
        }
    }
}

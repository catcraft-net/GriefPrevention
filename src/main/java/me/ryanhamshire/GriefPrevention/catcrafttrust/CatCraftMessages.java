package me.ryanhamshire.GriefPrevention.catcrafttrust;

public final class CatCraftMessages
{
    public static final String PREFIX = "&b[CatCraft]";

    private CatCraftMessages()
    {
    }

    public static String usage(String commandName)
    {
        return PREFIX + " &eUse /" + commandName + " <player> [time].";
    }

    public static String invalidDuration()
    {
        return PREFIX + " &eInvalid duration.";
    }

    public static String invalidTarget()
    {
        return PREFIX + " &eInvalid target.";
    }

    public static String unavailable()
    {
        return PREFIX + " &eTrust service is unavailable.";
    }
}

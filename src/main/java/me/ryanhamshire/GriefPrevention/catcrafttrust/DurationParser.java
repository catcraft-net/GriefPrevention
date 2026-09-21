package me.ryanhamshire.GriefPrevention.catcrafttrust;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser
{
    private static final Pattern TOKEN = Pattern.compile("^(-?\\d+)([mhdw])$", Pattern.CASE_INSENSITIVE);

    private DurationParser()
    {
    }

    public static Duration parse(String text, Duration maximum) throws InvalidDurationException
    {
        Objects.requireNonNull(maximum, "maximum");
        if (maximum.isNegative()) throw new IllegalArgumentException("maximum must be non-negative");

        if (text == null) throw new InvalidDurationException(InvalidDurationReason.INVALID_FORMAT);
        String input = text;
        if (input.equalsIgnoreCase("forever")) return Duration.ZERO;

        Matcher matcher = TOKEN.matcher(input);
        if (!matcher.matches()) throw new InvalidDurationException(InvalidDurationReason.INVALID_FORMAT);

        final long amount;
        try
        {
            amount = Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException ex)
        {
            throw new InvalidDurationException(InvalidDurationReason.OVERFLOW, ex);
        }
        if (amount <= 0) throw new InvalidDurationException(InvalidDurationReason.MUST_BE_POSITIVE);

        long multiplier = switch (Character.toLowerCase(matcher.group(2).charAt(0)))
        {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 604_800_000L;
            default -> throw new InvalidDurationException(InvalidDurationReason.INVALID_FORMAT);
        };

        final long millis;
        try
        {
            millis = Math.multiplyExact(amount, multiplier);
        }
        catch (ArithmeticException ex)
        {
            throw new InvalidDurationException(InvalidDurationReason.OVERFLOW, ex);
        }
        Duration result = Duration.ofMillis(millis);
        if (result.compareTo(maximum) > 0) throw new InvalidDurationException(InvalidDurationReason.TOO_LONG);
        return result;
    }
}

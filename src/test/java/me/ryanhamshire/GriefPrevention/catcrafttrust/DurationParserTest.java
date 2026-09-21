package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationParserTest
{
    private static final Duration MAXIMUM = Duration.ofDays(30);

    @Test
    void parsesSupportedUnitsCaseInsensitively() throws InvalidDurationException
    {
        assertEquals(Duration.ofMinutes(30), DurationParser.parse("30m", MAXIMUM));
        assertEquals(Duration.ofHours(1), DurationParser.parse("1H", MAXIMUM));
        assertEquals(Duration.ofDays(1), DurationParser.parse("1d", MAXIMUM));
        assertEquals(Duration.ofDays(14), DurationParser.parse("2W", MAXIMUM));
    }

    @Test
    void parsesForeverAsZero() throws InvalidDurationException
    {
        assertEquals(Duration.ZERO, DurationParser.parse("forever", MAXIMUM));
    }

    @Test
    void rejectsZeroAndNegativeDurationsAsNonPositive()
    {
        assertEquals(InvalidDurationReason.MUST_BE_POSITIVE,
                assertThrows(InvalidDurationException.class,
                        () -> DurationParser.parse("0m", MAXIMUM)).reason());
        assertEquals(InvalidDurationReason.MUST_BE_POSITIVE,
                assertThrows(InvalidDurationException.class,
                        () -> DurationParser.parse("-1h", MAXIMUM)).reason());
    }

    @Test
    void rejectsMalformedDurations()
    {
        for (String input : new String[]{"1.5h", "30", "30mtrailing", "", "   "})
        {
            assertEquals(InvalidDurationReason.INVALID_FORMAT,
                    assertThrows(InvalidDurationException.class,
                            () -> DurationParser.parse(input, MAXIMUM)).reason(), input);
        }
    }

    @Test
    void rejectsDurationsLongerThanMaximum()
    {
        assertEquals(InvalidDurationReason.TOO_LONG,
                assertThrows(InvalidDurationException.class,
                        () -> DurationParser.parse("31d", MAXIMUM)).reason());
    }

    @Test
    void rejectsMultiplicationOverflow()
    {
        assertEquals(InvalidDurationReason.OVERFLOW,
                assertThrows(InvalidDurationException.class,
                        () -> DurationParser.parse("999999999999999999w", MAXIMUM)).reason());
    }
}

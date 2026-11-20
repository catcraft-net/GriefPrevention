package me.ryanhamshire.GriefPrevention;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrappedExitFinderTest
{
    private static TrappedExitFinder.ClaimLookup claims(int[]... boxes)
    {
        List<int[]> list = List.of(boxes);
        return (x, z) -> {
            for (int[] b : list)
                if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) return b;
            return null;
        };
    }

    private static final TrappedExitFinder.ColumnFilter ANY = (x, z) -> true;

    @Test
    void longStripClaimExitsSideways()
    {
        // Blue 2026-11-18: claim 81537 x -4730..-4723, z 16728..29000; player at -4728,21005
        int[] exit = TrappedExitFinder.find(-4728, 21005, claims(new int[] { -4730, 16728, -4723, 29000 }), ANY);
        assertArrayEquals(new int[] { -4731, 21005 }, exit);
    }

    @Test
    void picksNearestEdge()
    {
        int[] exit = TrappedExitFinder.find(8, 1, claims(new int[] { 0, 0, 10, 10 }), ANY);
        assertArrayEquals(new int[] { 8, -1 }, exit);
    }

    @Test
    void neighbouringClaimIsWalkedThrough()
    {
        // tall claim A x 0..10, claim B directly west x -20..-1; east of A is the short way out
        int[] exit = TrappedExitFinder.find(1, 5,
                claims(new int[] { 0, -100, 10, 100 }, new int[] { -20, -100, -1, 100 }), ANY);
        assertArrayEquals(new int[] { 11, 5 }, exit);
    }

    @Test
    void surroundedClaimEscapesThroughNextOne()
    {
        // inner claim fully inside a ring of four claims; nearest gap is north of the north claim
        int[] exit = TrappedExitFinder.find(5, 1, claims(
                new int[] { 0, 0, 10, 10 },
                new int[] { -5, -3, 15, -1 },
                new int[] { -5, 11, 15, 30 },
                new int[] { -30, 0, -1, 10 },
                new int[] { 11, 0, 30, 10 }), ANY);
        assertArrayEquals(new int[] { 5, -4 }, exit);
    }

    @Test
    void borderFilterSkipsBlockedExit()
    {
        int[] exit = TrappedExitFinder.find(8, 2, claims(new int[] { 0, 0, 10, 10 }), (x, z) -> z >= 0);
        assertArrayEquals(new int[] { 11, 2 }, exit);
    }

    @Test
    void givesUpWhenNothingFound()
    {
        assertNull(TrappedExitFinder.find(0, 0, claims(new int[] { -100, -100, 100, 100 }), (x, z) -> false));
    }
}

package me.ryanhamshire.GriefPrevention;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Finds the nearest unclaimed column for /trapped.
 *
 * <p>Upstream GriefPrevention always jumped to the north-west corner of the claim, so a long strip claim
 * (a road or rail line) sent players thousands of blocks away. This walks out of the nearest edge instead:
 * best-first over the four straight-line exits of each claim the search lands in, closest to the start first.</p>
 */
final class TrappedExitFinder
{
    /** Top-level claim bounds (inclusive) covering a column, or null when the column is unclaimed. */
    interface ClaimLookup
    {
        int @Nullable [] boundsAt(int x, int z);
    }

    /** Whether a column may be used as a destination (inside the world border). */
    interface ColumnFilter
    {
        boolean allowed(int x, int z);
    }

    static final int MAX_CLAIMS_VISITED = 64;

    private TrappedExitFinder() {}

    /** @return {x, z} of the nearest reachable unclaimed column, or null if none was found within the limit. */
    static int @Nullable [] find(int startX, int startZ, @NotNull ClaimLookup claims, @NotNull ColumnFilter filter)
    {
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[2], b[2]));
        Set<Long> seenColumns = new HashSet<>();
        Set<String> visitedClaims = new HashSet<>();
        open.add(new long[] { startX, startZ, 0 });

        while (!open.isEmpty())
        {
            long[] next = open.poll();
            int x = (int) next[0];
            int z = (int) next[1];
            if (!seenColumns.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;

            int[] bounds = claims.boundsAt(x, z);
            if (bounds == null)
            {
                if (filter.allowed(x, z)) return new int[] { x, z };
                continue;
            }

            String key = bounds[0] + "," + bounds[1] + "," + bounds[2] + "," + bounds[3];
            if (!visitedClaims.add(key)) continue;
            if (visitedClaims.size() > MAX_CLAIMS_VISITED) return null;

            int[][] exits = {
                    { bounds[0] - 1, z },
                    { bounds[2] + 1, z },
                    { x, bounds[1] - 1 },
                    { x, bounds[3] + 1 },
            };
            for (int[] exit : exits)
            {
                long dx = exit[0] - startX;
                long dz = exit[1] - startZ;
                open.add(new long[] { exit[0], exit[1], dx * dx + dz * dz });
            }
        }
        return null;
    }
}

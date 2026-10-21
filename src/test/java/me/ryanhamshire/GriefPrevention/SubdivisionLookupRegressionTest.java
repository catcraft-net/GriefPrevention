package me.ryanhamshire.GriefPrevention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Red 2026-10-20: subdivisions were never put in the claim ID map, so getClaim(id) missed them and
 * /untrust inside a claim with subdivisions failed with "claim N no longer exists".
 */
class SubdivisionLookupRegressionTest
{
    @Test
    void subdivisionsAreFindableByIdAndForgottenWhenDeleted()
    {
        SubdivisionFixture fixture = new SubdivisionFixture();
        Claim parent = fixture.add(1L, 0, 63, null);
        Claim child = fixture.add(2L, 8, 15, parent);

        assertSame(parent, fixture.store.getClaim(1L));
        assertSame(child, fixture.store.getClaim(2L));

        fixture.delete(child);
        assertNull(fixture.store.getClaim(2L));
        assertSame(parent, fixture.store.getClaim(1L));
    }

    @Test
    void deletingAParentForgetsItsSubdivisions()
    {
        SubdivisionFixture fixture = new SubdivisionFixture();
        Claim parent = fixture.add(1L, 0, 63, null);
        fixture.add(2L, 8, 15, parent);

        fixture.delete(parent);
        assertNull(fixture.store.getClaim(1L));
        assertNull(fixture.store.getClaim(2L));
    }
}

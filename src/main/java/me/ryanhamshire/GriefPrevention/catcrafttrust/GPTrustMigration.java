package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Strict, one-time reader for GPTrust v1/v2 primitive state. */
final class GPTrustMigration
{
    private static final int MAX_VALUE_LENGTH = 2048;

    private GPTrustMigration()
    {
    }

    static int importIfPresent(Path legacyFile, CatCraftTrustStateStore destination,
                               DataStore dataStore, int maximumRecords) throws IOException
    {
        if (!Files.isRegularFile(legacyFile)) return 0;
        long maxBytes = Math.max(65_536L, Math.multiplyExact((long) maximumRecords, 4096L));
        long size = Files.size(legacyFile);
        if (size < 0L || size > maxBytes) throw new IOException("GPTrust state file exceeds configured bound");
        byte[] raw = Files.readAllBytes(legacyFile);
        if (raw.length > maxBytes) throw new IOException("GPTrust state file exceeds configured bound");

        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(raw));
        String version = properties.getProperty("version");
        if (!"1".equals(version) && !"2".equals(version))
            throw new IOException("Unsupported GPTrust state version");
        int count = parseInt(properties.getProperty("count", "0"), "record count");
        if (count < 0 || count > maximumRecords)
            throw new IOException("GPTrust record count exceeds configured bound");
        for (String key : properties.stringPropertyNames())
        {
            if ("version".equals(key) || "count".equals(key)) continue;
            if (!key.matches("record\\.\\d+")) throw new IOException("Unexpected GPTrust state key");
            int index = parseInt(key.substring("record.".length()), "record index");
            if (index < 0 || index >= count) throw new IOException("Unexpected GPTrust record index");
            String value = properties.getProperty(key);
            if (value == null || value.length() > MAX_VALUE_LENGTH)
                throw new IOException("Invalid GPTrust record value");
        }

        int imported = 0;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < count; index++)
        {
            String encoded = properties.getProperty("record." + index);
            if (encoded == null) throw new IOException("Missing GPTrust record " + index);
            LegacyRecord legacy;
            try
            {
                legacy = decode(encoded);
            }
            catch (RuntimeException failure)
            {
                throw new IOException("Invalid GPTrust record " + index, failure);
            }
            if (!seen.add(legacy.key())) throw new IOException("Duplicate GPTrust record " + index);
            if (legacy.kind() == LegacyKind.REVOKE || (legacy.expiresAt() == 0L
                    && legacy.kind() != LegacyKind.BUILD)) continue;
            Claim claim = dataStore.getClaim(legacy.claimId());
            if (claim == null || !java.util.Objects.equals(claim.getOwnerID(), legacy.ownerId())) continue;
            NativeTrustState expected = expectedState(legacy);
            String target = legacy.target().toLowerCase(Locale.ROOT);
            boolean nativeMatches = legacy.dimension() == TrustDimension.PERMISSION
                    ? claim.getPermission(target) == expected.permission()
                    : claim.managers.contains(target) == expected.manager();
            if (!nativeMatches) continue;
            CatCraftTrustKind kind = switch (legacy.kind())
            {
                case ACCESS -> CatCraftTrustKind.ACCESS;
                case BUILD -> CatCraftTrustKind.BUILD;
                case CONTAINER -> CatCraftTrustKind.CONTAINER;
                case FULL -> CatCraftTrustKind.FULL;
                case PERMISSION -> CatCraftTrustKind.MANAGE;
                case REVOKE -> throw new IllegalStateException("revoke was filtered");
            };
            TemporaryTrustRecord record = new TemporaryTrustRecord(legacy.claimId(), target, kind,
                    legacy.dimension(), legacy.restore(), expected, legacy.expiresAt(),
                    legacy.revision(), legacy.ownerId());
            if (destination.get(record.key()).isPresent()) continue;
            destination.put(record);
            imported++;
        }

        destination.save();
        Path migrated = legacyFile.resolveSibling(legacyFile.getFileName() + ".migrated");
        try
        {
            Files.move(legacyFile, migrated, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException unsupported)
        {
            Files.move(legacyFile, migrated);
        }
        return imported;
    }

    private static LegacyRecord decode(String value)
    {
        String[] fields = value.split("\\|", -1);
        if (fields.length != 9 && fields.length != 10)
            throw new IllegalArgumentException("invalid field count");
        long claimId = Long.parseLong(fields[0]);
        if (claimId <= 0L) throw new IllegalArgumentException("invalid claim id");
        String target = decodeText(fields[1]).trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty() || target.length() > TemporaryTrustRecord.MAX_TARGET_LENGTH)
            throw new IllegalArgumentException("invalid target");
        LegacyKind kind = LegacyKind.valueOf(fields[2]);
        int offset = fields.length == 10 ? 1 : 0;
        TrustDimension dimension = fields.length == 10
                ? TrustDimension.valueOf(fields[3])
                : kind == LegacyKind.PERMISSION ? TrustDimension.MANAGER : TrustDimension.PERMISSION;
        ClaimPermission permission = "-".equals(fields[3 + offset])
                ? null : ClaimPermission.valueOf(fields[3 + offset]);
        NativeTrustState restore = new NativeTrustState(permission,
                parseBoolean(fields[4 + offset]), parseBoolean(fields[5 + offset]));
        long expiresAt = Long.parseLong(fields[6 + offset]);
        long revision = Long.parseLong(fields[7 + offset]);
        if (expiresAt < 0L || revision <= 0L) throw new IllegalArgumentException("invalid time or revision");
        UUID owner = "-".equals(fields[8 + offset]) ? null
                : UUID.fromString(decodeText(fields[8 + offset]));
        if (kind != LegacyKind.REVOKE)
        {
            TrustDimension expectedDimension = kind == LegacyKind.PERMISSION
                    ? TrustDimension.MANAGER : TrustDimension.PERMISSION;
            if (dimension != expectedDimension) throw new IllegalArgumentException("dimension mismatch");
        }
        return new LegacyRecord(claimId, target, kind, dimension, restore, expiresAt, revision, owner);
    }

    private static NativeTrustState expectedState(LegacyRecord record)
    {
        NativeTrustState previous = record.restore();
        return switch (record.kind())
        {
            case ACCESS -> new NativeTrustState(ClaimPermission.Access, previous.manager(), false);
            case BUILD -> new NativeTrustState(ClaimPermission.Access, previous.manager(), true);
            case CONTAINER -> new NativeTrustState(ClaimPermission.Inventory, previous.manager(), false);
            case FULL -> new NativeTrustState(ClaimPermission.Build, previous.manager(), false);
            case PERMISSION -> new NativeTrustState(previous.permission(), true, previous.safeBuild());
            case REVOKE -> throw new IllegalArgumentException("revoke records are not imported");
        };
    }

    private static String decodeText(String value)
    {
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        String text = new String(decoded, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(decoded, text.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("invalid UTF-8");
        return text;
    }

    private static boolean parseBoolean(String value)
    {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }

    private static int parseInt(String value, String label) throws IOException
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException failure)
        {
            throw new IOException("Invalid GPTrust " + label, failure);
        }
    }

    private enum LegacyKind { ACCESS, BUILD, CONTAINER, FULL, PERMISSION, REVOKE }

    private record LegacyRecord(long claimId, String target, LegacyKind kind,
                                TrustDimension dimension, NativeTrustState restore,
                                long expiresAt, long revision, UUID ownerId)
    {
        String key()
        {
            return claimId + "|" + dimension + "|" + target;
        }
    }
}

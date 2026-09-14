package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.UUID;

import me.ryanhamshire.GriefPrevention.ClaimPermission;

/**
 * Bounded persistent storage for the primitive CatCraft trust journal.
 * All indexes are rebuilt from the versioned file during startup and contain
 * no Bukkit objects.
 */
public final class CatCraftTrustStateStore
{
    private static final String VERSION = "2";
    private static final int FIELD_COUNT = 13;

    private final Path file;
    private final int maximumRecords;
    private final Map<String, TemporaryTrustRecord> records = new LinkedHashMap<>();
    private final Map<Long, Map<String, TemporaryTrustRecord>> byClaim = new LinkedHashMap<>();
    private final PriorityQueue<ExpiryKey> expirationQueue = new PriorityQueue<>(
            Comparator.comparingLong(ExpiryKey::expiresAt).thenComparingLong(ExpiryKey::revision));
    private long nextRevision = 1L;

    public CatCraftTrustStateStore(Path file, int maximumRecords)
    {
        this.file = Objects.requireNonNull(file, "file");
        if (maximumRecords <= 0) throw new IllegalArgumentException("maximumRecords must be positive");
        this.maximumRecords = maximumRecords;
    }

    public synchronized void load() throws IOException
    {
        ParsedState parsed;
        if (!Files.isRegularFile(file))
        {
            if (!Files.isRegularFile(backupFile()))
            {
                clear();
                return;
            }
            parsed = parseFile(backupFile());
        }
        else
        {
            try
            {
                parsed = parseFile(file);
            }
            catch (IOException primaryFailure)
            {
                if (!Files.isRegularFile(backupFile())) throw primaryFailure;
                try
                {
                    parsed = parseFile(backupFile());
                }
                catch (IOException backupFailure)
                {
                    primaryFailure.addSuppressed(backupFailure);
                    throw primaryFailure;
                }
            }
        }

        records.clear();
        byClaim.clear();
        expirationQueue.clear();
        records.putAll(parsed.records());
        for (TemporaryTrustRecord record : parsed.records().values())
        {
            indexClaim(record);
            if (record.expiresAtMillis() > 0)
            {
                expirationQueue.add(new ExpiryKey(record.key(), record.revision(), record.expiresAtMillis()));
            }
        }
        nextRevision = parsed.nextRevision();
    }

    public synchronized void save() throws IOException
    {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path temporary = temporaryFile();
        Path backup = backupFile();
        boolean moved = false;
        try
        {
            Properties properties = new Properties();
            properties.setProperty("version", VERSION);
            List<TemporaryTrustRecord> snapshot = new ArrayList<>(records.values());
            properties.setProperty("count", Integer.toString(snapshot.size()));
            for (int index = 0; index < snapshot.size(); index++)
            {
                properties.setProperty("record." + index, encode(snapshot.get(index)));
            }

            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                 Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8))
            {
                properties.store(writer, "CatCraft temporary trust state");
                writer.flush();
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                channel.force(true);
            }
            if (Files.isRegularFile(absolute))
            {
                Files.copy(absolute, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try
            {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException unsupported)
            {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        }
        finally
        {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    public synchronized Optional<TemporaryTrustRecord> get(String key)
    {
        return Optional.ofNullable(records.get(key));
    }

    public synchronized Optional<TemporaryTrustRecord> get(long claimId, String target, TrustDimension dimension)
    {
        return get(key(claimId, target, dimension));
    }

    public synchronized List<TemporaryTrustRecord> forClaim(long claimId)
    {
        Map<String, TemporaryTrustRecord> rows = byClaim.get(claimId);
        return rows == null ? List.of() : List.copyOf(rows.values());
    }

    public synchronized List<TemporaryTrustRecord> values()
    {
        return List.copyOf(records.values());
    }

    public synchronized Optional<TemporaryTrustRecord> findSafeBuild(long claimId,
                                                                       @Nullable UUID checked,
                                                                       @Nullable Player player,
                                                                       long now,
                                                                       @Nullable UUID ownerId)
    {
        Map<String, TemporaryTrustRecord> rows = byClaim.get(claimId);
        if (rows == null) return Optional.empty();
        for (TemporaryTrustRecord record : rows.values())
        {
            if (!isLiveSafeBuild(record, now, ownerId)) continue;
            if (targetMatches(record.target(), checked, player)) return Optional.of(record);
        }
        return Optional.empty();
    }

    public synchronized Optional<TemporaryTrustRecord> findAnySafeBuild(long claimId,
                                                                          long now,
                                                                          @Nullable UUID ownerId)
    {
        Map<String, TemporaryTrustRecord> rows = byClaim.get(claimId);
        if (rows == null) return Optional.empty();
        for (TemporaryTrustRecord record : rows.values())
        {
            if (isLiveSafeBuild(record, now, ownerId)) return Optional.of(record);
        }
        return Optional.empty();
    }

    public synchronized Optional<TemporaryTrustRecord> nextExpiring()
    {
        while (!expirationQueue.isEmpty())
        {
            ExpiryKey expiry = expirationQueue.peek();
            TemporaryTrustRecord current = records.get(expiry.key());
            if (current == null || current.revision() != expiry.revision()
                    || current.expiresAtMillis() != expiry.expiresAt() || current.expiresAtMillis() <= 0)
            {
                expirationQueue.poll();
                continue;
            }
            return Optional.of(current);
        }
        return Optional.empty();
    }

    public synchronized long nextRevision()
    {
        if (nextRevision == Long.MAX_VALUE) throw new IllegalStateException("trust revision exhausted");
        return nextRevision++;
    }

    public synchronized void put(TemporaryTrustRecord record)
    {
        Objects.requireNonNull(record, "record");
        if (!records.containsKey(record.key()) && records.size() >= maximumRecords)
        {
            throw new IllegalStateException("CatCraft temporary trust record limit reached");
        }
        putInternal(record);
        advanceRevision(record.revision());
    }

    public synchronized Optional<TemporaryTrustRecord> remove(String key)
    {
        return Optional.ofNullable(removeInternal(key));
    }

    public synchronized Optional<TemporaryTrustRecord> removeIfRevision(String key, long revision)
    {
        TemporaryTrustRecord current = records.get(key);
        if (current == null || current.revision() != revision) return Optional.empty();
        return Optional.ofNullable(removeInternal(key));
    }

    public synchronized int removeClaim(long claimId)
    {
        Map<String, TemporaryTrustRecord> rows = byClaim.remove(claimId);
        if (rows == null) return 0;
        int removed = 0;
        for (String key : rows.keySet())
        {
            if (records.remove(key) != null) removed++;
        }
        return removed;
    }

    public synchronized int removeTarget(long claimId, String target, TrustDimension dimension)
    {
        return removeInternal(key(claimId, target, dimension)) == null ? 0 : 1;
    }

    public synchronized int removeTargetAll(long claimId, String target)
    {
        int removed = 0;
        for (TrustDimension dimension : TrustDimension.values())
        {
            removed += removeTarget(claimId, target, dimension);
        }
        return removed;
    }

    public synchronized int size()
    {
        return records.size();
    }

    static String key(long claimId, String target, TrustDimension dimension)
    {
        return claimId + "|" + dimension + "|" + target.toLowerCase(Locale.ROOT);
    }

    private boolean isLiveSafeBuild(TemporaryTrustRecord record, long now, @Nullable UUID ownerId)
    {
        return record.dimension() == TrustDimension.PERMISSION
                && record.appliedKind() == CatCraftTrustKind.BUILD
                && record.expectedState().safeBuild()
                && (record.expiresAtMillis() == 0L || record.expiresAtMillis() > now)
                && Objects.equals(record.ownerIdAtGrant(), ownerId);
    }

    private static boolean targetMatches(String target, @Nullable UUID checked, @Nullable Player player)
    {
        if ("public".equals(target)) return true;
        if (target.startsWith("[") && target.endsWith("]"))
        {
            return player != null && player.hasPermission(target.substring(1, target.length() - 1));
        }
        return checked != null && target.equalsIgnoreCase(checked.toString());
    }

    private void putInternal(TemporaryTrustRecord record)
    {
        TemporaryTrustRecord previous = records.put(record.key(), record);
        if (previous != null)
        {
            Map<String, TemporaryTrustRecord> oldRows = byClaim.get(previous.claimId());
            if (oldRows != null)
            {
                oldRows.remove(previous.key());
                if (oldRows.isEmpty()) byClaim.remove(previous.claimId());
            }
        }
        indexClaim(record);
        if (record.expiresAtMillis() > 0)
        {
            expirationQueue.add(new ExpiryKey(record.key(), record.revision(), record.expiresAtMillis()));
        }
    }

    private void indexClaim(TemporaryTrustRecord record)
    {
        byClaim.computeIfAbsent(record.claimId(), ignored -> new LinkedHashMap<>()).put(record.key(), record);
    }

    private TemporaryTrustRecord removeInternal(String key)
    {
        TemporaryTrustRecord removed = records.remove(key);
        if (removed == null) return null;
        Map<String, TemporaryTrustRecord> rows = byClaim.get(removed.claimId());
        if (rows != null)
        {
            rows.remove(key);
            if (rows.isEmpty()) byClaim.remove(removed.claimId());
        }
        return removed;
    }

    private void advanceRevision(long revision)
    {
        if (revision >= nextRevision)
        {
            nextRevision = revision == Long.MAX_VALUE ? Long.MAX_VALUE : revision + 1L;
        }
    }

    private void clear()
    {
        records.clear();
        byClaim.clear();
        expirationQueue.clear();
        nextRevision = 1L;
    }

    private ParsedState parseFile(Path source) throws IOException
    {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(source), StandardCharsets.UTF_8))
        {
            properties.load(reader);
        }
        if (!VERSION.equals(properties.getProperty("version")))
        {
            throw new IOException("Unsupported CatCraft trust state version");
        }
        int count;
        try
        {
            count = Integer.parseInt(properties.getProperty("count", "-1"));
        }
        catch (NumberFormatException ex)
        {
            throw new IOException("Invalid CatCraft trust record count", ex);
        }
        if (count < 0 || count > maximumRecords)
        {
            throw new IOException("CatCraft trust record count exceeds configured bound");
        }

        Map<String, TemporaryTrustRecord> parsed = new LinkedHashMap<>();
        long parsedNextRevision = 1L;
        for (int index = 0; index < count; index++)
        {
            String value = properties.getProperty("record." + index);
            if (value == null) throw new IOException("Missing CatCraft trust record " + index);
            try
            {
                TemporaryTrustRecord record = decode(value);
                if (parsed.put(record.key(), record) != null)
                {
                    throw new IOException("Duplicate CatCraft trust record " + index);
                }
                long candidate = record.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : record.revision() + 1L;
                parsedNextRevision = Math.max(parsedNextRevision, candidate);
            }
            catch (IOException ex)
            {
                throw ex;
            }
            catch (RuntimeException ex)
            {
                throw new IOException("Invalid CatCraft trust record " + index, ex);
            }
        }
        return new ParsedState(parsed, parsedNextRevision);
    }

    private static String encode(TemporaryTrustRecord record)
    {
        NativeTrustState previous = record.previousState();
        NativeTrustState expected = record.expectedState();
        return String.join("|",
                Long.toString(record.claimId()),
                encodeText(record.target()),
                record.appliedKind().name(),
                record.dimension().name(),
                permissionName(previous.permission()),
                Boolean.toString(previous.manager()),
                Boolean.toString(previous.safeBuild()),
                permissionName(expected.permission()),
                Boolean.toString(expected.manager()),
                Boolean.toString(expected.safeBuild()),
                Long.toString(record.expiresAtMillis()),
                Long.toString(record.revision()),
                record.ownerIdAtGrant() == null ? "-" : encodeText(record.ownerIdAtGrant().toString()));
    }

    private static TemporaryTrustRecord decode(String value)
    {
        String[] fields = value.split("\\|", -1);
        if (fields.length != FIELD_COUNT) throw new IllegalArgumentException("invalid CatCraft trust field count");
        long claimId = Long.parseLong(fields[0]);
        String target = decodeText(fields[1]);
        CatCraftTrustKind kind = CatCraftTrustKind.valueOf(fields[2]);
        TrustDimension dimension = TrustDimension.valueOf(fields[3]);
        NativeTrustState previous = new NativeTrustState(
                parsePermission(fields[4]), parseBoolean(fields[5]), parseBoolean(fields[6]));
        NativeTrustState expected = new NativeTrustState(
                parsePermission(fields[7]), parseBoolean(fields[8]), parseBoolean(fields[9]));
        long expires = Long.parseLong(fields[10]);
        long revision = Long.parseLong(fields[11]);
        UUID owner = "-".equals(fields[12]) ? null : UUID.fromString(decodeText(fields[12]));
        return new TemporaryTrustRecord(claimId, target, kind, dimension, previous, expected,
                expires, revision, owner);
    }

    private static String permissionName(@Nullable ClaimPermission permission)
    {
        return permission == null ? "-" : permission.name();
    }

    private static @Nullable ClaimPermission parsePermission(String value)
    {
        return "-".equals(value) ? null : ClaimPermission.valueOf(value);
    }

    private static boolean parseBoolean(String value)
    {
        if (!"true".equals(value) && !"false".equals(value))
        {
            throw new IllegalArgumentException("invalid boolean");
        }
        return Boolean.parseBoolean(value);
    }

    private static String encodeText(String value)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value)
    {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private Path temporaryFile()
    {
        return file.resolveSibling(file.getFileName() + ".tmp");
    }

    private Path backupFile()
    {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private record ExpiryKey(String key, long revision, long expiresAt)
    {
    }

    private record ParsedState(Map<String, TemporaryTrustRecord> records, long nextRevision)
    {
    }
}

package mrnavastar.invsync.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Called under the player's session lock, before applying the first shared snapshot. */
public final class LocalPlayerArchive {
    private LocalPlayerArchive() {}

    public static void capture(Path world, UUID loginId) throws IOException {
        Path directory = world.resolve("invsync-local-originals");
        Path archive = directory.resolve(loginId + ".zip");
        if (Files.isRegularFile(archive)) return;
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, loginId + "-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                Path players = world.resolve("players");
                addIfPresent(zip, players, "data/" + loginId + ".dat");
                addIfPresent(zip, players, "advancements/" + loginId + ".json");
                addIfPresent(zip, players, "stats/" + loginId + ".json");
            }
            // An empty archive records that this server had no local save at first contact.
            Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void addIfPresent(ZipOutputStream zip, Path root, String name) throws IOException {
        Path source = root.resolve(name);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(source, BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            return;
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(attributes.lastModifiedTime());
        zip.putNextEntry(entry);
        Files.copy(source, zip);
        zip.closeEntry();
    }
}

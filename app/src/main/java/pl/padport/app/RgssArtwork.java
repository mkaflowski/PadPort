package pl.padport.app;

import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;

/** Card artwork for RGSS games: a Graphics/Titles image, loose or inside the RGSSAD archive. */
final class RgssArtwork {
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    static byte[] titleImage(GameSource source) throws IOException {
        for (String path : RgssGame.titleImages(source.entries.keySet())) {
            GameSource.Entry entry = source.entries.get(path);
            if (entry != null && entry.size() > 0 && entry.size() < MAX_BYTES) return source.read(path, MAX_BYTES);
        }
        for (String ext : RgssGame.ARCHIVES) {
            GameSource.Entry archive = source.entry(source.exec + ext);
            if (archive == null) continue;
            try (ParcelFileDescriptor fd = source.context.getContentResolver().openFileDescriptor(
                     DocumentsContract.buildDocumentUriUsingTree(source.tree, archive.documentId()), "r");
                 FileInputStream stream = new FileInputStream(fd.getFileDescriptor());
                 FileChannel channel = stream.getChannel()) {
                RgssGame.Reader reader = reader(channel);
                List<RgssGame.ArchiveEntry> entries = RgssGame.archiveEntries(reader.bytes(0, 8), reader);
                Map<String, RgssGame.ArchiveEntry> byName = new HashMap<>();
                for (RgssGame.ArchiveEntry e : entries) byName.put(e.name(), e);
                for (String name : RgssGame.titleImages(byName.keySet())) {
                    RgssGame.ArchiveEntry e = byName.get(name);
                    if (e.size() > 0 && e.size() < MAX_BYTES) return RgssGame.archiveData(e, reader);
                }
            }
        }
        return null;
    }

    private static RgssGame.Reader reader(FileChannel channel) throws IOException {
        long size = channel.size();
        return new RgssGame.Reader() {
            public long length() { return size; }
            public byte[] bytes(long position, int length) throws IOException {
                if (position < 0 || length < 0 || position + length > size) throw new IOException("Truncated RGSS archive");
                ByteBuffer buffer = ByteBuffer.allocate(length);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, position + buffer.position()) < 0) throw new IOException("Truncated RGSS archive");
                }
                return buffer.array();
            }
        };
    }
}

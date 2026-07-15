package ca.pkay.rcloneexplorer.thumbnails;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;

import ca.pkay.rcloneexplorer.util.FLog;

/**
 * JNI bridge for the bundled, thumbnail-only FFmpeg build.
 *
 * <p>The native side never opens a URL or a local path. FFmpeg reads exclusively through a
 * custom {@code AVIOContext}; its read/seek callbacks are forwarded to {@link RandomAccessSource}
 * so remote data continues to flow through Round-Sync's authenticated rclone range reader.</p>
 */
final class FfmpegThumbnailExtractor {

    interface RandomAccessSource extends Closeable {
        int readAt(long position, byte[] buffer, int offset, int size) throws IOException;

        long getSize() throws IOException;
    }

    private static final String TAG = "FfmpegThumbnail";
    private static final boolean AVAILABLE;

    static {
        boolean available = false;
        try {
            System.loadLibrary("roundsync_ffmpeg");
            available = true;
        } catch (LinkageError error) {
            FLog.d(TAG, "Bundled FFmpeg thumbnail fallback is unavailable: %s",
                    error.getMessage());
        }
        AVAILABLE = available;
    }

    private FfmpegThumbnailExtractor() {
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    @Nullable
    static Bitmap extractFrame(RandomAccessSource source, double targetFraction,
            int maximumDimension) {
        if (!AVAILABLE || source == null || maximumDimension <= 0) {
            return null;
        }
        try {
            long sourceSize = source.getSize();
            Bitmap bitmap = nativeExtractFrame(
                    source, sourceSize, targetFraction, maximumDimension);
            if (bitmap != null) {
                // Decoded video frames are opaque; keep the repository's JPEG cache path.
                bitmap.setHasAlpha(false);
            }
            return bitmap;
        } catch (IOException | RuntimeException | LinkageError error) {
            FLog.d(TAG, "Bundled FFmpeg thumbnail extraction failed: %s",
                    error.getMessage());
            return null;
        }
    }

    @Nullable
    private static native Bitmap nativeExtractFrame(RandomAccessSource source, long sourceSize,
            double targetFraction, int maximumDimension);
}

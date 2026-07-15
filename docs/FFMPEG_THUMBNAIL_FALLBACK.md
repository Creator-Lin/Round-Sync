# FFmpeg video-thumbnail fallback

## Why this exists

Round-Sync first uses Android `MediaMetadataRetriever`, because the platform path is small and can
use device codecs. That path is not reliable for every valid media file: codec availability varies
by Android release and vendor, and some ASF/WMV, AVI, MOV, Matroska and transport-stream files are
rejected because of container metadata, timestamps or indexes. The fallback is therefore triggered
for **every video format** whenever the platform extractor returns no frame or throws; it is not an
extension-based WMV special case.

## Data path

For a non-SAF remote, no complete temporary video is created:

```text
FFmpeg demuxer/decoder
  -> custom AVIOContext read_packet / seek
  -> JNI RandomAccessSource.readAt(position, ...)
  -> RcloneRangeMediaDataSource
  -> rclone cat remote:path --offset position --count length
  -> remote object
```

`RcloneRangeMediaDataSource` keeps three 4-8 MiB read-ahead ranges. Small and repeated FFmpeg probes
therefore reuse memory instead of starting an rclone process for each 64 KiB AVIO read. `SEEK_SET`,
`SEEK_CUR`, `SEEK_END` and `AVSEEK_SIZE` are implemented. Unknown-size sources still support absolute
seeks but cannot service end-relative seeks until a size becomes available.

SAF fallback reads positionally from an `AssetFileDescriptor`/`FileChannel`; it also avoids copying
the full video when the provider exposes a seekable descriptor.

## Extraction behavior

1. A target around one third of the duration is selected, using the same randomized fraction as the
   platform path.
2. FFmpeg probes up to 8 MiB and analyzes up to five seconds of media time.
3. It seeks backward to a suitable packet and decodes forward to the first usable frame at or after
   the target. If duration or seeking is unavailable, it decodes from the beginning.
4. Corrupt frames are ignored. The latest valid frame remains a last-resort candidate if EOF is
   reached before the exact target.
5. `libswscale` converts to RGBA and limits the longest side to 1280 pixels. Display-matrix rotations
   near 0/90/180/270 degrees are applied before returning an Android `Bitmap`.
6. Native work is bounded by a 90-second deadline, 256 MiB of AVIO-delivered bytes, 12,000 video
   packets and 1,200 decoded frames.

## Reduced FFmpeg build

The complete FFmpeg 8.1.2 corresponding source is in `ffmpeg/ffmpeg-src`. `ffmpeg/build.gradle`
builds `libroundsync_ffmpeg.so` for all four existing app ABIs with Android NDK 25.2.9519653.
The configuration is LGPL-only and disables programs, network protocols, encoders, filters,
devices, external compression libraries and assembly. It enables only:

- `libavformat`, `libavcodec`, `libavutil` and `libswscale`;
- common video demuxers, including ASF, AVI, Matroska/WebM, MOV/MP4, FLV, MPEG-PS/TS, Ogg and RM;
- WMV1/2/3, VC-1, MSMPEG4, H.264, HEVC, MPEG-1/2/4, VP8/9, AV1 and a selected set of common legacy
  and professional software video decoders.

The resulting per-ABI JNI library is copied to `app/lib/<abi>/libroundsync_ffmpeg.so`, so the normal
APK packaging path embeds it. FFmpeg symbols are hidden and unused sections are removed. The linker
uses a 16 KiB maximum ELF page size.

The source build needs `bash` and GNU `make`; the CI workflow's Ubuntu environment already provides
them. Native build outputs are cached under `ffmpeg/cache/` and are not source-controlled.

## Failure and cache semantics

If the bundled library cannot be loaded, the app continues with the platform behavior and records a
normal thumbnail failure. If both platform and FFmpeg extraction fail, the existing 12-hour negative
cache is written. The thumbnail cache version was changed, so failures from the pre-FFmpeg pipeline
do not suppress a new extraction attempt after upgrade.

## Recommended device tests

Test at least one file in each category through a genuine remote backend, not only local storage:

- ASF with WMV2, WMV3 and VC-1 video, including a file whose index is at the end;
- MP4/MOV with a tail `moov`, MKV/WebM, AVI and MPEG-TS;
- unknown-size or non-seekable provider behavior;
- slow/erroring remotes, truncated files and files with bad timestamps;
- all ABIs used by release builds, with special attention to 32-bit devices;
- cache regeneration after upgrading from a build that produced a negative marker.

Use debug logging to confirm that the FFmpeg path is entered only after the platform path fails and
that rclone requests cover metadata/index/sample ranges rather than the complete object.

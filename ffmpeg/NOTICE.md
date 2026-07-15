# Bundled FFmpeg thumbnail component

This module contains FFmpeg 8.1.2 source code and builds a reduced JNI library used only as a
video-thumbnail fallback. The build disables command-line programs, network protocols, encoders,
filters, devices, GPL components and non-free components. It enables selected demuxers and software
video decoders plus libswscale. Media input is supplied by Round-Sync through a custom AVIOContext.

FFmpeg is licensed under the GNU Lesser General Public License, version 2.1 or later, for the
configuration used here. The complete corresponding source and FFmpeg's license files are included
under `ffmpeg-src/`. See `ffmpeg-src/LICENSE.md` for details.

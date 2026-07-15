#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <errno.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libavutil/display.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/mathematics.h>
#include <libavutil/mem.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>

#define LOG_TAG "RoundSyncFFmpeg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define AVIO_BUFFER_SIZE (64 * 1024)
#define MAX_NATIVE_IO_BYTES (256LL * 1024LL * 1024LL)
#define EXTRACTION_DEADLINE_US (90LL * 1000LL * 1000LL)
#define MAX_VIDEO_PACKETS 12000
#define MAX_DECODED_FRAMES 1200

typedef struct JavaAvioContext {
    JNIEnv *env;
    jobject source;
    jmethodID read_at_method;
    jmethodID get_size_method;
    jbyteArray scratch;
    jsize scratch_capacity;
    int64_t position;
    int64_t size;
    int64_t total_read;
    int64_t deadline_us;
    int failed;
} JavaAvioContext;

static void log_ffmpeg_error(const char *operation, int error_code) {
    char message[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(error_code, message, sizeof(message));
    LOGD("%s failed: %s (%d)", operation, message, error_code);
}

static int deadline_expired(const JavaAvioContext *context) {
    return context->deadline_us > 0 && av_gettime_relative() >= context->deadline_us;
}

static int ensure_scratch(JavaAvioContext *context, int requested) {
    if (requested <= context->scratch_capacity && context->scratch != NULL) {
        return 0;
    }
    if (context->scratch != NULL) {
        (*context->env)->DeleteLocalRef(context->env, context->scratch);
        context->scratch = NULL;
        context->scratch_capacity = 0;
    }
    context->scratch = (*context->env)->NewByteArray(context->env, requested);
    if (context->scratch == NULL || (*context->env)->ExceptionCheck(context->env)) {
        (*context->env)->ExceptionClear(context->env);
        context->failed = 1;
        return AVERROR(ENOMEM);
    }
    context->scratch_capacity = requested;
    return 0;
}

static int java_read_packet(void *opaque, uint8_t *buffer, int buffer_size) {
    JavaAvioContext *context = (JavaAvioContext *) opaque;
    if (context == NULL || context->failed) {
        return AVERROR(EIO);
    }
    if (deadline_expired(context)) {
        context->failed = 1;
        return AVERROR(ETIMEDOUT);
    }
    if (buffer_size <= 0) {
        return 0;
    }
    if (context->size >= 0 && context->position >= context->size) {
        return AVERROR_EOF;
    }
    if (context->total_read >= MAX_NATIVE_IO_BYTES) {
        context->failed = 1;
        return AVERROR(EFBIG);
    }

    int requested = buffer_size;
    if (context->size >= 0) {
        int64_t remaining = context->size - context->position;
        if (remaining <= 0) {
            return AVERROR_EOF;
        }
        if (remaining < requested) {
            requested = (int) remaining;
        }
    }
    int64_t budget = MAX_NATIVE_IO_BYTES - context->total_read;
    if (budget < requested) {
        requested = (int) budget;
    }
    if (requested <= 0) {
        context->failed = 1;
        return AVERROR(EFBIG);
    }

    int result = ensure_scratch(context, requested);
    if (result < 0) {
        return result;
    }

    jint read = (*context->env)->CallIntMethod(
            context->env,
            context->source,
            context->read_at_method,
            (jlong) context->position,
            context->scratch,
            0,
            requested);
    if ((*context->env)->ExceptionCheck(context->env)) {
        (*context->env)->ExceptionClear(context->env);
        context->failed = 1;
        return AVERROR(EIO);
    }
    if (read < 0) {
        return AVERROR_EOF;
    }
    if (read == 0 || read > requested) {
        context->failed = 1;
        return AVERROR(EIO);
    }

    (*context->env)->GetByteArrayRegion(
            context->env, context->scratch, 0, read, (jbyte *) buffer);
    if ((*context->env)->ExceptionCheck(context->env)) {
        (*context->env)->ExceptionClear(context->env);
        context->failed = 1;
        return AVERROR(EIO);
    }

    context->position += read;
    context->total_read += read;
    return read;
}

static int64_t checked_add(int64_t left, int64_t right, int *valid) {
    if ((right > 0 && left > INT64_MAX - right)
            || (right < 0 && left < INT64_MIN - right)) {
        *valid = 0;
        return 0;
    }
    *valid = 1;
    return left + right;
}

static int64_t java_seek(void *opaque, int64_t offset, int whence) {
    JavaAvioContext *context = (JavaAvioContext *) opaque;
    if (context == NULL || context->failed) {
        return AVERROR(EIO);
    }
    if (deadline_expired(context)) {
        context->failed = 1;
        return AVERROR(ETIMEDOUT);
    }

    if (whence == AVSEEK_SIZE) {
        if (context->size >= 0) {
            return context->size;
        }
        jlong size = (*context->env)->CallLongMethod(
                context->env, context->source, context->get_size_method);
        if ((*context->env)->ExceptionCheck(context->env)) {
            (*context->env)->ExceptionClear(context->env);
            return AVERROR(ENOSYS);
        }
        if (size >= 0) {
            context->size = size;
            return size;
        }
        return AVERROR(ENOSYS);
    }

    whence &= ~AVSEEK_FORCE;
    int64_t base;
    switch (whence) {
        case SEEK_SET:
            base = 0;
            break;
        case SEEK_CUR:
            base = context->position;
            break;
        case SEEK_END:
            if (context->size < 0) {
                return AVERROR(ENOSYS);
            }
            base = context->size;
            break;
        default:
            return AVERROR(EINVAL);
    }

    int valid = 0;
    int64_t next = checked_add(base, offset, &valid);
    if (!valid || next < 0) {
        return AVERROR(EINVAL);
    }
    context->position = next;
    return next;
}

static int ffmpeg_interrupt(void *opaque) {
    JavaAvioContext *context = (JavaAvioContext *) opaque;
    return context == NULL || context->failed || deadline_expired(context);
}

static int normalized_clockwise_rotation(const AVStream *stream) {
    const AVPacketSideData *side_data = av_packet_side_data_get(
            stream->codecpar->coded_side_data,
            stream->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX);
    if (side_data == NULL || side_data->size < 9 * (int) sizeof(int32_t)) {
        return 0;
    }

    double counter_clockwise = av_display_rotation_get((const int32_t *) side_data->data);
    if (isnan(counter_clockwise)) {
        return 0;
    }
    int clockwise = (int) lround(-counter_clockwise);
    clockwise %= 360;
    if (clockwise < 0) {
        clockwise += 360;
    }

    int nearest = ((clockwise + 45) / 90 * 90) % 360;
    int difference = abs(clockwise - nearest);
    difference = difference > 180 ? 360 - difference : difference;
    return difference <= 5 ? nearest : 0;
}

static int64_t target_timestamp_for_stream(
        const AVFormatContext *format,
        const AVStream *stream,
        double target_fraction) {
    if (!isfinite(target_fraction)) {
        target_fraction = 1.0 / 3.0;
    }
    if (target_fraction < 0.02) {
        target_fraction = 0.02;
    } else if (target_fraction > 0.98) {
        target_fraction = 0.98;
    }

    if (stream->duration != AV_NOPTS_VALUE && stream->duration > 0) {
        int64_t start = stream->start_time == AV_NOPTS_VALUE ? 0 : stream->start_time;
        return start + (int64_t) llround(stream->duration * target_fraction);
    }

    if (format->duration != AV_NOPTS_VALUE && format->duration > 0) {
        int64_t start_us = format->start_time == AV_NOPTS_VALUE ? 0 : format->start_time;
        int64_t target_us = start_us + (int64_t) llround(format->duration * target_fraction);
        return av_rescale_q(target_us, AV_TIME_BASE_Q, stream->time_base);
    }
    return AV_NOPTS_VALUE;
}

static int is_usable_frame(const AVFrame *frame) {
    return frame != NULL
            && frame->width > 0
            && frame->height > 0
            && frame->format >= 0
            && !(frame->flags & AV_FRAME_FLAG_CORRUPT);
}

static int consider_decoded_frame(
        const AVFrame *decoded,
        AVFrame *candidate,
        int64_t target_timestamp,
        int *selected) {
    if (!is_usable_frame(decoded)) {
        return 0;
    }

    av_frame_unref(candidate);
    int ref_result = av_frame_ref(candidate, decoded);
    if (ref_result < 0) {
        return ref_result;
    }

    int64_t timestamp = decoded->best_effort_timestamp;
    if (target_timestamp == AV_NOPTS_VALUE
            || timestamp == AV_NOPTS_VALUE
            || timestamp >= target_timestamp) {
        *selected = 1;
    }
    return 0;
}

static int decode_thumbnail_frame(
        AVFormatContext *format,
        AVCodecContext *decoder,
        int video_stream_index,
        int64_t target_timestamp,
        JavaAvioContext *io_context,
        AVFrame *candidate) {
    AVPacket *packet = av_packet_alloc();
    AVFrame *decoded = av_frame_alloc();
    if (packet == NULL || decoded == NULL) {
        av_packet_free(&packet);
        av_frame_free(&decoded);
        return AVERROR(ENOMEM);
    }

    int selected = 0;
    int video_packets = 0;
    int decoded_frames = 0;
    int read_result = 0;

    while (!selected
            && video_packets < MAX_VIDEO_PACKETS
            && decoded_frames < MAX_DECODED_FRAMES
            && !ffmpeg_interrupt(io_context)) {
        read_result = av_read_frame(format, packet);
        if (read_result < 0) {
            break;
        }
        if (packet->stream_index != video_stream_index) {
            av_packet_unref(packet);
            continue;
        }
        video_packets++;

        int send_result = avcodec_send_packet(decoder, packet);
        av_packet_unref(packet);
        if (send_result < 0 && send_result != AVERROR(EAGAIN)) {
            continue;
        }

        for (;;) {
            int receive_result = avcodec_receive_frame(decoder, decoded);
            if (receive_result == AVERROR(EAGAIN) || receive_result == AVERROR_EOF) {
                break;
            }
            if (receive_result < 0) {
                break;
            }
            decoded_frames++;
            int consider_result = consider_decoded_frame(
                    decoded, candidate, target_timestamp, &selected);
            av_frame_unref(decoded);
            if (consider_result < 0) {
                av_packet_free(&packet);
                av_frame_free(&decoded);
                return consider_result;
            }
            if (selected || decoded_frames >= MAX_DECODED_FRAMES) {
                break;
            }
        }
    }

    if (!selected && !ffmpeg_interrupt(io_context)) {
        avcodec_send_packet(decoder, NULL);
        for (;;) {
            int receive_result = avcodec_receive_frame(decoder, decoded);
            if (receive_result == AVERROR_EOF || receive_result == AVERROR(EAGAIN)) {
                break;
            }
            if (receive_result < 0) {
                break;
            }
            int consider_result = consider_decoded_frame(
                    decoded, candidate, target_timestamp, &selected);
            av_frame_unref(decoded);
            if (consider_result < 0) {
                av_packet_free(&packet);
                av_frame_free(&decoded);
                return consider_result;
            }
            if (selected) {
                break;
            }
        }
    }

    av_packet_free(&packet);
    av_frame_free(&decoded);

    if (candidate->width > 0 && candidate->height > 0) {
        return 0;
    }
    if (ffmpeg_interrupt(io_context)) {
        return AVERROR(ETIMEDOUT);
    }
    return read_result < 0 ? read_result : AVERROR_INVALIDDATA;
}

static void fit_inside(int width, int height, int maximum, int *out_width, int *out_height) {
    int largest = width > height ? width : height;
    if (largest <= maximum) {
        *out_width = width;
        *out_height = height;
        return;
    }
    double scale = maximum / (double) largest;
    *out_width = (int) llround(width * scale);
    *out_height = (int) llround(height * scale);
    if (*out_width < 1) {
        *out_width = 1;
    }
    if (*out_height < 1) {
        *out_height = 1;
    }
}

static jobject create_android_bitmap(JNIEnv *env, int width, int height) {
    jclass bitmap_class = (*env)->FindClass(env, "android/graphics/Bitmap");
    jclass config_class = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
    if (bitmap_class == NULL || config_class == NULL) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    jfieldID argb_field = (*env)->GetStaticFieldID(
            env,
            config_class,
            "ARGB_8888",
            "Landroid/graphics/Bitmap$Config;");
    jmethodID create_method = (*env)->GetStaticMethodID(
            env,
            bitmap_class,
            "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (argb_field == NULL || create_method == NULL) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    jobject config = (*env)->GetStaticObjectField(env, config_class, argb_field);
    jobject bitmap = (*env)->CallStaticObjectMethod(
            env, bitmap_class, create_method, width, height, config);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    return bitmap;
}

static void copy_rotated_rgba(
        const uint8_t *source,
        int source_stride,
        int source_width,
        int source_height,
        uint8_t *destination,
        int destination_stride,
        int rotation) {
    for (int y = 0; y < source_height; y++) {
        const uint8_t *source_row = source + (int64_t) y * source_stride;
        for (int x = 0; x < source_width; x++) {
            int destination_x;
            int destination_y;
            switch (rotation) {
                case 90:
                    destination_x = source_height - 1 - y;
                    destination_y = x;
                    break;
                case 180:
                    destination_x = source_width - 1 - x;
                    destination_y = source_height - 1 - y;
                    break;
                case 270:
                    destination_x = y;
                    destination_y = source_width - 1 - x;
                    break;
                default:
                    destination_x = x;
                    destination_y = y;
                    break;
            }
            memcpy(destination + (int64_t) destination_y * destination_stride
                            + destination_x * 4,
                    source_row + x * 4,
                    4);
        }
    }
}

static jobject frame_to_bitmap(
        JNIEnv *env,
        const AVFrame *frame,
        int maximum_dimension,
        int rotation) {
    int scaled_width;
    int scaled_height;
    fit_inside(frame->width, frame->height, maximum_dimension, &scaled_width, &scaled_height);

    uint8_t *rgba_data[4] = {0};
    int rgba_linesize[4] = {0};
    int allocated = av_image_alloc(
            rgba_data,
            rgba_linesize,
            scaled_width,
            scaled_height,
            AV_PIX_FMT_RGBA,
            1);
    if (allocated < 0) {
        log_ffmpeg_error("av_image_alloc", allocated);
        return NULL;
    }

    struct SwsContext *scaler = sws_getContext(
            frame->width,
            frame->height,
            (enum AVPixelFormat) frame->format,
            scaled_width,
            scaled_height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);
    if (scaler == NULL) {
        av_freep(&rgba_data[0]);
        return NULL;
    }

    int scaled_rows = sws_scale(
            scaler,
            (const uint8_t *const *) frame->data,
            frame->linesize,
            0,
            frame->height,
            rgba_data,
            rgba_linesize);
    sws_freeContext(scaler);
    if (scaled_rows <= 0) {
        av_freep(&rgba_data[0]);
        return NULL;
    }

    int bitmap_width = (rotation == 90 || rotation == 270) ? scaled_height : scaled_width;
    int bitmap_height = (rotation == 90 || rotation == 270) ? scaled_width : scaled_height;
    jobject bitmap = create_android_bitmap(env, bitmap_width, bitmap_height);
    if (bitmap == NULL) {
        av_freep(&rgba_data[0]);
        return NULL;
    }

    AndroidBitmapInfo bitmap_info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS
            || bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
            || AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS
            || pixels == NULL) {
        av_freep(&rgba_data[0]);
        return NULL;
    }

    copy_rotated_rgba(
            rgba_data[0],
            rgba_linesize[0],
            scaled_width,
            scaled_height,
            (uint8_t *) pixels,
            (int) bitmap_info.stride,
            rotation);

    AndroidBitmap_unlockPixels(env, bitmap);
    av_freep(&rgba_data[0]);
    return bitmap;
}

JNIEXPORT jobject JNICALL
Java_ca_pkay_rcloneexplorer_thumbnails_FfmpegThumbnailExtractor_nativeExtractFrame(
        JNIEnv *env,
        jclass clazz,
        jobject source,
        jlong source_size,
        jdouble target_fraction,
        jint maximum_dimension) {
    (void) clazz;
    if (source == NULL || maximum_dimension <= 0) {
        return NULL;
    }

    av_log_set_level(AV_LOG_ERROR);

    JavaAvioContext java_io;
    memset(&java_io, 0, sizeof(java_io));
    java_io.env = env;
    java_io.source = source;
    java_io.size = source_size >= 0 ? source_size : -1;
    java_io.deadline_us = av_gettime_relative() + EXTRACTION_DEADLINE_US;

    jclass source_class = (*env)->GetObjectClass(env, source);
    if (source_class == NULL) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    java_io.read_at_method = (*env)->GetMethodID(env, source_class, "readAt", "(J[BII)I");
    java_io.get_size_method = (*env)->GetMethodID(env, source_class, "getSize", "()J");
    if (java_io.read_at_method == NULL || java_io.get_size_method == NULL) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    uint8_t *avio_buffer = av_malloc(AVIO_BUFFER_SIZE);
    if (avio_buffer == NULL) {
        return NULL;
    }
    AVIOContext *avio = avio_alloc_context(
            avio_buffer,
            AVIO_BUFFER_SIZE,
            0,
            &java_io,
            java_read_packet,
            NULL,
            java_seek);
    if (avio == NULL) {
        av_free(avio_buffer);
        return NULL;
    }
    avio->seekable = AVIO_SEEKABLE_NORMAL;

    AVFormatContext *format = avformat_alloc_context();
    if (format == NULL) {
        avio_context_free(&avio);
        return NULL;
    }
    format->pb = avio;
    format->flags |= AVFMT_FLAG_CUSTOM_IO | AVFMT_FLAG_FAST_SEEK;
    format->interrupt_callback.callback = ffmpeg_interrupt;
    format->interrupt_callback.opaque = &java_io;

    AVDictionary *format_options = NULL;
    av_dict_set(&format_options, "probesize", "8388608", 0);
    av_dict_set(&format_options, "analyzeduration", "5000000", 0);

    int result = avformat_open_input(&format, NULL, NULL, &format_options);
    av_dict_free(&format_options);
    if (result < 0) {
        log_ffmpeg_error("avformat_open_input", result);
        if (format != NULL) {
            avformat_free_context(format);
        }
        avio_context_free(&avio);
        if (java_io.scratch != NULL) {
            (*env)->DeleteLocalRef(env, java_io.scratch);
        }
        return NULL;
    }

    result = avformat_find_stream_info(format, NULL);
    if (result < 0) {
        log_ffmpeg_error("avformat_find_stream_info", result);
        // Some damaged or unusual containers still expose a decodable video stream.
    }

    const AVCodec *decoder_codec = NULL;
    int video_stream_index = av_find_best_stream(
            format, AVMEDIA_TYPE_VIDEO, -1, -1, &decoder_codec, 0);
    if (video_stream_index < 0 || decoder_codec == NULL) {
        log_ffmpeg_error("av_find_best_stream", video_stream_index);
        avformat_close_input(&format);
        avio_context_free(&avio);
        if (java_io.scratch != NULL) {
            (*env)->DeleteLocalRef(env, java_io.scratch);
        }
        return NULL;
    }

    AVStream *video_stream = format->streams[video_stream_index];
    AVCodecContext *decoder = avcodec_alloc_context3(decoder_codec);
    if (decoder == NULL) {
        avformat_close_input(&format);
        avio_context_free(&avio);
        if (java_io.scratch != NULL) {
            (*env)->DeleteLocalRef(env, java_io.scratch);
        }
        return NULL;
    }

    result = avcodec_parameters_to_context(decoder, video_stream->codecpar);
    if (result >= 0) {
        decoder->thread_count = 2;
        result = avcodec_open2(decoder, decoder_codec, NULL);
    }
    if (result < 0) {
        log_ffmpeg_error("avcodec_open2", result);
        avcodec_free_context(&decoder);
        avformat_close_input(&format);
        avio_context_free(&avio);
        if (java_io.scratch != NULL) {
            (*env)->DeleteLocalRef(env, java_io.scratch);
        }
        return NULL;
    }

    int64_t target_timestamp = target_timestamp_for_stream(
            format, video_stream, target_fraction);
    if (target_timestamp != AV_NOPTS_VALUE) {
        result = avformat_seek_file(
                format,
                video_stream_index,
                INT64_MIN,
                target_timestamp,
                target_timestamp,
                AVSEEK_FLAG_BACKWARD);
        if (result < 0) {
            result = av_seek_frame(
                    format, video_stream_index, target_timestamp, AVSEEK_FLAG_BACKWARD);
        }
        if (result >= 0) {
            avcodec_flush_buffers(decoder);
        } else {
            // Fall back to decoding from the beginning rather than failing the whole request.
            avio_seek(avio, 0, SEEK_SET);
            avformat_flush(format);
            avcodec_flush_buffers(decoder);
            target_timestamp = AV_NOPTS_VALUE;
        }
    }

    AVFrame *candidate = av_frame_alloc();
    jobject bitmap = NULL;
    if (candidate != NULL) {
        result = decode_thumbnail_frame(
                format,
                decoder,
                video_stream_index,
                target_timestamp,
                &java_io,
                candidate);
        if (result >= 0 && candidate->width > 0 && candidate->height > 0) {
            int rotation = normalized_clockwise_rotation(video_stream);
            bitmap = frame_to_bitmap(env, candidate, maximum_dimension, rotation);
        } else if (result < 0) {
            log_ffmpeg_error("decode_thumbnail_frame", result);
        }
    }

    av_frame_free(&candidate);
    avcodec_free_context(&decoder);
    avformat_close_input(&format);
    avio_context_free(&avio);
    if (java_io.scratch != NULL) {
        (*env)->DeleteLocalRef(env, java_io.scratch);
    }
    return bitmap;
}

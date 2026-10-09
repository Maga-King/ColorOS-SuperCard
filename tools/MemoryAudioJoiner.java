package dev.local.supercardhost;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Joins the original recorder's AAC packets in draft order, without decoding,
 * re-encoding, deleting sources, uploading, or creating a transcription.
 *
 * Call from a worker thread. Allocate a new empty .m4a destination using the
 * private MemoryTransferProvider first; the caller grants its resulting URI to
 * AIMemory and submits it as the single type-401 item in the existing batch.
 * Only retire the original attachment IDs after the entire original batch's
 * real saved/canDeleteSources acknowledgement. A failed join preserves sources
 * and removes only its incomplete destination.
 */
public final class MemoryAudioJoiner {
    public static final long MAX_DURATION_MS = 62_000;
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final String AUTHORITY = "dev.local.supercardhost.memory-transfer";
    private static final String AAC = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44_100;
    private static final int CHANNELS = 1;
    private static final int MAX_SEGMENTS = 13;
    private static final int MAX_PACKET_BYTES = 256 * 1024;
    private static final long FRAME_US = Math.round(1024.0 * 1_000_000 / SAMPLE_RATE);

    private MemoryAudioJoiner() { }

    public record Result(Uri uri, long durationMs, int segments, int samples, long bytes) { }

    /** A content-free, user-facing failure; every source remains untouched. */
    public static final class JoinException extends IOException {
        JoinException(String reason) { super(reason + "，录音草稿已完整保留"); }
    }

    public static Result join(Context context, List<Uri> sources, Uri destination) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new JoinException("录音拼接需要在后台执行");
        }
        if (context == null || sources == null || sources.size() < 2 || sources.size() > MAX_SEGMENTS) {
            throw new JoinException("录音拼接支持 2 至 13 段");
        }
        List<Uri> input = new ArrayList<>(sources);
        HashSet<Uri> unique = new HashSet<>();
        for (Uri uri : input) {
            if (uri == null || !"content".equals(uri.getScheme()) || !unique.add(uri)) {
                throw new JoinException("录音来源无效或重复");
            }
        }
        validateDestination(destination);
        if (unique.contains(destination) || itemSize(context, destination) != 0) {
            throw new JoinException("录音输出必须是新建的空临时文件");
        }
        byte[] config = null;
        // Reject incompatible inputs before touching even the temporary output.
        for (Uri uri : input) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, uri, null);
                byte[] candidate = validateTrack(extractor);
                if (config == null) config = candidate;
                else if (!Arrays.equals(config, candidate)) throw new JoinException("录音编码参数不同，无法无损拼接");
            } catch (JoinException error) { throw error; }
            catch (IOException | RuntimeException error) { throw new JoinException("有一段录音无法读取"); }
            finally { extractor.release(); }
        }

        boolean claimed = false;
        try {
            MessageDigest expected = sha256();
            ArrayList<Long> timestamps = new ArrayList<>();
            long endUs = 0, packetBytes = 0;
            ByteBuffer packet = ByteBuffer.allocateDirect(MAX_PACKET_BYTES);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try (ParcelFileDescriptor output = context.getContentResolver().openFileDescriptor(destination, "wt")) {
                if (output == null) throw new JoinException("无法创建录音临时文件");
                claimed = true;
                MediaMuxer muxer = new MediaMuxer(output.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                boolean started = false;
                try {
                    MediaFormat format = MediaFormat.createAudioFormat(AAC, SAMPLE_RATE, CHANNELS);
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
                    int track = muxer.addTrack(format);
                    muxer.start(); started = true;
                    for (Uri uri : input) {
                        MediaExtractor extractor = new MediaExtractor();
                        try {
                            extractor.setDataSource(context, uri, null);
                            if (!Arrays.equals(config, validateTrack(extractor))) {
                                throw new JoinException("录音在拼接前发生变化");
                            }
                            extractor.selectTrack(0);
                            long first = Long.MIN_VALUE, previous = Long.MIN_VALUE, last = -1;
                            int count = 0;
                            while (extractor.getSampleSize() >= 0) {
                                checkInterrupted();
                                int size = checkedSize(extractor);
                                int flags = extractor.getSampleFlags();
                                if ((flags & (MediaExtractor.SAMPLE_FLAG_ENCRYPTED | MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)) != 0) {
                                    throw new JoinException("录音含无法直接拼接的加密或不完整音频帧");
                                }
                                long sourceTime = extractor.getSampleTime();
                                if (first == Long.MIN_VALUE) first = sourceTime;
                                else if (sourceTime <= previous || sourceTime - previous > FRAME_US * 2) {
                                    throw new JoinException("录音时间轴不连续，无法确保全部声音完整");
                                }
                                previous = sourceTime;
                                long pts = Math.addExact(endUs, Math.subtractExact(sourceTime, first));
                                if (pts < 0 || pts + FRAME_US > MAX_DURATION_MS * 1000) {
                                    throw new JoinException("多段录音合计超过小布随口记 62 秒限制");
                                }
                                packet.clear();
                                if (extractor.readSampleData(packet, 0) != size) {
                                    throw new JoinException("录音音频帧读取不完整");
                                }
                                packet.position(0); packet.limit(size);
                                packetBytes += size;
                                if (packetBytes > MAX_BYTES - 64 * 1024) throw new JoinException("拼接录音超过小布 20 MiB 限制");
                                addPacket(expected, packet, size);
                                info.set(0, size, pts, (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                                muxer.writeSampleData(track, packet, info);
                                timestamps.add(pts); last = pts; count++;
                                if (!extractor.advance()) break;
                            }
                            if (count == 0 || last < 0) throw new JoinException("有一段录音没有可播放音频帧");
                            // Preserve every AAC frame, including each recorder's original
                            // priming/padding. Do not trim sounds or add synthetic silence.
                            endUs = last + FRAME_US;
                        } finally { extractor.release(); }
                    }
                    // Android's MPEG4 muxer supports an empty EOS packet to specify
                    // the final frame duration. This is not an extra audio frame.
                    packet.clear();
                    info.set(0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    muxer.writeSampleData(track, packet, info);
                    muxer.stop(); started = false;
                } finally {
                    if (started) try { muxer.stop(); } catch (RuntimeException ignored) { }
                    muxer.release();
                }
            }
            long size = itemSize(context, destination);
            if (size <= 0 || size > MAX_BYTES) throw new JoinException("拼接后的录音大小不符合小布限制");
            long durationMs = verify(context, destination, config, timestamps, expected.digest(), endUs);
            return new Result(destination, durationMs, input.size(), timestamps.size(), size);
        } catch (JoinException error) {
            if (claimed) discardOutput(context, destination);
            throw error;
        } catch (IOException | RuntimeException error) {
            if (claimed) discardOutput(context, destination);
            throw new JoinException("录音拼接或完整性核对失败");
        }
    }

    private static long verify(Context context, Uri uri, byte[] config, List<Long> timestamps,
            byte[] expected, long endUs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MessageDigest actual = sha256();
        try {
            extractor.setDataSource(context, uri, null);
            if (!Arrays.equals(config, validateTrack(extractor))) throw new JoinException("拼接后的录音编码发生变化");
            extractor.selectTrack(0);
            ByteBuffer packet = ByteBuffer.allocateDirect(MAX_PACKET_BYTES);
            int count = 0;
            long previous = Long.MIN_VALUE;
            while (extractor.getSampleSize() >= 0) {
                checkInterrupted();
                int size = checkedSize(extractor);
                long pts = extractor.getSampleTime();
                if (count >= timestamps.size() || pts <= previous
                        || Math.abs(pts - timestamps.get(count)) > 1000) {
                    throw new JoinException("拼接后的录音顺序或时间轴校验失败");
                }
                previous = pts;
                packet.clear();
                if (extractor.readSampleData(packet, 0) != size) throw new JoinException("拼接后的录音读取不完整");
                packet.position(0); packet.limit(size);
                addPacket(actual, packet, size);
                count++;
                if (!extractor.advance()) break;
            }
            if (count != timestamps.size() || !Arrays.equals(expected, actual.digest())) {
                throw new JoinException("拼接后存在丢失、重复或变化的音频帧");
            }
        } finally { extractor.release(); }
        try (MediaMetadataRetriever media = new MediaMetadataRetriever()) {
            media.setDataSource(context, uri);
            if (!"yes".equals(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) {
                throw new JoinException("拼接结果没有可播放音轨");
            }
            String duration = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = duration == null ? -1 : Long.parseLong(duration);
            if (durationMs < 1000 || durationMs > MAX_DURATION_MS
                    || Math.abs(durationMs * 1000 - endUs) > FRAME_US * 2) {
                throw new JoinException("拼接后的实际时长不符合小布 1 至 62 秒限制");
            }
            return durationMs;
        }
    }

    private static byte[] validateTrack(MediaExtractor extractor) throws JoinException {
        if (extractor.getTrackCount() != 1) throw new JoinException("录音必须只有一条 AAC 音轨");
        MediaFormat format = extractor.getTrackFormat(0);
        if (!AAC.equals(format.getString(MediaFormat.KEY_MIME))
                || format.getInteger(MediaFormat.KEY_SAMPLE_RATE, -1) != SAMPLE_RATE
                || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, -1) != CHANNELS) {
            throw new JoinException("录音必须使用原录音器的 44.1 kHz 单声道 AAC 编码");
        }
        ByteBuffer raw = format.getByteBuffer("csd-0");
        if (raw == null || raw.remaining() < 2 || raw.remaining() > 64) throw new JoinException("录音缺少有效 AAC 编码参数");
        ByteBuffer data = raw.duplicate(); byte[] config = new byte[data.remaining()]; data.get(config);
        // AAC-LC, 1024 samples/frame. A different frame length must not silently
        // use this timeline calculation or be concatenated into the same track.
        if (((config[0] & 0xff) >>> 3) != MediaCodecInfo.CodecProfileLevel.AACObjectLC
                || (((config[0] & 7) << 1) | ((config[1] & 0xff) >>> 7)) != 4
                || ((config[1] >>> 3) & 15) != CHANNELS
                || (config[1] & 4) != 0) throw new JoinException("此 AAC 编码类型无法按原音频帧无损拼接");
        for (int i = 1; i <= 2; i++) if (format.getByteBuffer("csd-" + i) != null) {
            throw new JoinException("录音含不同的扩展编码配置");
        }
        return config;
    }

    private static int checkedSize(MediaExtractor extractor) throws JoinException {
        long size = extractor.getSampleSize();
        if (size <= 0 || size > MAX_PACKET_BYTES || extractor.getSampleTrackIndex() != 0) {
            throw new JoinException("录音包含无效音频帧");
        }
        return (int) size;
    }

    private static void addPacket(MessageDigest digest, ByteBuffer packet, int size) {
        digest.update((byte) (size >>> 24)); digest.update((byte) (size >>> 16));
        digest.update((byte) (size >>> 8)); digest.update((byte) size);
        digest.update(packet.duplicate());
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable"); }
    }

    private static long itemSize(Context context, Uri uri) throws IOException {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) {
                throw new JoinException("无法核对录音临时文件大小");
            }
            return cursor.getLong(0);
        } catch (RuntimeException error) { throw new JoinException("无法访问录音临时文件"); }
    }

    private static void validateDestination(Uri uri) throws JoinException {
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null) throw new JoinException("录音输出必须使用私有临时存储");
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 3 || !"items".equals(parts.get(0))
                || !parts.get(1).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !parts.get(2).matches("[A-Za-z0-9_.-]{1,96}\\.m4a")) {
            throw new JoinException("录音输出路径无效");
        }
    }

    private static void checkInterrupted() throws JoinException {
        if (Thread.currentThread().isInterrupted()) throw new JoinException("录音拼接已中断");
    }

    private static void discardOutput(Context context, Uri uri) {
        try { context.getContentResolver().delete(uri, null, null); }
        catch (RuntimeException ignored) { /* Only this incomplete output may remain. */ }
    }
}

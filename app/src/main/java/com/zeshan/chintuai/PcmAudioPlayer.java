package com.zeshan.chintuai;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streams Gemini Live 24 kHz PCM output without blocking the WebSocket callback thread. */
public final class PcmAudioPlayer {
    public interface Listener {
        void onPlaybackState(boolean playing);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final Listener listener;
    private AudioTrack track;
    private volatile long lastChunkAt;

    public PcmAudioPlayer(Listener listener) {
        this.listener = listener;
    }

    public void enqueue(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || released.get()) return;
        byte[] copy = pcm.clone();
        lastChunkAt = android.os.SystemClock.uptimeMillis();
        if (listener != null) listener.onPlaybackState(true);
        executor.execute(() -> {
            if (released.get()) return;
            AudioTrack audioTrack = ensureTrack();
            if (audioTrack == null) return;
            try {
                audioTrack.write(copy, 0, copy.length, AudioTrack.WRITE_BLOCKING);
            } catch (RuntimeException ignored) {
                // A broken vendor audio route should not crash Wazir's voice process.
            }
        });
    }

    public void markTurnComplete() {
        executor.execute(() -> {
            if (released.get()) return;
            long quietFor = android.os.SystemClock.uptimeMillis() - lastChunkAt;
            if (quietFor < 350L) {
                try {
                    Thread.sleep(350L - quietFor);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            if (listener != null) listener.onPlaybackState(false);
        });
    }

    private AudioTrack ensureTrack() {
        if (track != null) return track;
        int min = AudioTrack.getMinBufferSize(
                GeminiLiveProtocol.OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, GeminiLiveProtocol.OUTPUT_SAMPLE_RATE * 2);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(GeminiLiveProtocol.OUTPUT_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(size)
                    .build();
            track.setVolume(1f);
            track.play();
            return track;
        } catch (RuntimeException error) {
            track = null;
            return null;
        }
    }

    public void stopNow() {
        executor.execute(() -> {
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.play();
                } catch (RuntimeException ignored) {
                }
            }
            if (listener != null) listener.onPlaybackState(false);
        });
    }

    public void release() {
        if (!released.compareAndSet(false, true)) return;
        executor.execute(() -> {
            if (track != null) {
                try {
                    track.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    track.release();
                } catch (RuntimeException ignored) {
                }
                track = null;
            }
        });
        executor.shutdown();
    }
}

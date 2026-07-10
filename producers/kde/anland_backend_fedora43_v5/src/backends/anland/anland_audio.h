#ifndef ANLAND_AUDIO_H
#define ANLAND_AUDIO_H

/*
 * Producer-side audio engine.
 *
 * Owns a persistent PipeWire thread-loop with two streams that live for the whole
 * KWin session, independent of whether a consumer is connected:
 *
 *   - a sink-monitor capture stream  -> desktop playback PCM written to the audio
 *                                        socket (heard on the Android speaker);
 *   - a virtual Audio/Source stream  <- microphone PCM read from the audio socket
 *                                        (so Linux apps can record the Android mic).
 *
 * The streams are NEVER torn down on consumer disconnect: while detached the capture
 * stream simply drops its PCM and the source feeds silence, so PipeWire (and every
 * recording app) never sees the device disappear. Only the socket fd is hot-swapped:
 * anland_audio_set_fd(fd) on (re)connect, anland_audio_set_fd(-1) on fallback. The fd
 * is borrowed -- the engine never closes it (display_producer owns its lifetime).
 */

#ifdef __cplusplus
extern "C" {
#endif

/* Create the thread-loop and both streams. Returns 0 on success, -1 on failure.
 * Idempotent: a second call while already started is a no-op returning 0. */
int  anland_audio_start(void);

/* Stop and destroy the engine. Safe to call when not started. */
void anland_audio_stop(void);

/* Point the engine at the current audio socket, or -1 to detach (drop/silence).
 * Re-pointing resets the mic buffer and re-announces the playback format. */
void anland_audio_set_fd(int audio_fd);

#ifdef __cplusplus
}
#endif

#endif

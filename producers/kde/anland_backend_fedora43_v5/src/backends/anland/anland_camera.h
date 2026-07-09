#ifndef ANLAND_CAMERA_H
#define ANLAND_CAMERA_H

/*
 * Producer-side camera engine.
 *
 * Owns a persistent PipeWire thread-loop for the whole KWin session (like the audio
 * engine), but unlike audio the camera *nodes* are dynamic: they exist only while a
 * consumer is connected and has handed over its camera resources.
 *
 * Lifecycle, driven by the backend state machine:
 *   - anland_camera_start()                      once, at backend init
 *   - on leaving fallback: the backend requests SERVICE_TYPE_CAMERA; when the
 *     consumer replies with { ctrl_fd, stream_fd_0..N-1 } the backend calls
 *     anland_camera_set_resources(), which creates one virtual Video/Source node per
 *     camera so Linux apps see them as webcams.
 *   - on entering fallback: anland_camera_clear() stops any recording, destroys the
 *     nodes and closes the fds.
 *   - anland_camera_stop()                       once, at backend teardown
 *
 * The camera only physically turns on when an app actually consumes a node: the node's
 * stream going live sends CAMERA_CTRL_START_RECORD to the consumer over ctrl_fd (and
 * STOP_RECORD when the last consumer goes away). Frames (I420) arrive on each stream_fd
 * and are emitted by that camera's Video/Source.
 *
 * All fds passed to set_resources are OWNED by the engine (SCM_RIGHTS dups) and closed
 * in clear()/stop().
 */

#ifdef __cplusplus
extern "C" {
#endif

/* Create the thread-loop and connect to PipeWire. Returns 0 on success, -1 on
 * failure. Idempotent: a second call while already started is a no-op returning 0. */
int  anland_camera_start(void);

/* Stop and destroy the engine (also clears any active resources). Safe when stopped. */
void anland_camera_stop(void);

/* Adopt the consumer's camera resources and build one Video/Source per camera.
 * fds layout matches the consumer's camera_allocate_resource(): fds[0] is the shared
 * control socket, fds[1..n_streams] are the per-camera stream sockets. Replaces any
 * previously-set resources. Takes ownership of all fds. */
void anland_camera_set_resources(int ctrl_fd, const int *stream_fds, int num_cameras);

/* Tear down the nodes, stop recording, and close all resource fds. Idempotent. */
void anland_camera_clear(void);

#ifdef __cplusplus
}
#endif

#endif /* ANLAND_CAMERA_H */

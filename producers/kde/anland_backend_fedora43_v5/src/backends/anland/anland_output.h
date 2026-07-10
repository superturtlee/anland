/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-License-Identifier: GPL-2.0-or-later

    Native output for the "anland" backend. The render cycle is paced by the
    display daemon's transport, not a software timer: present() hands the buffer
    to the consumer (a render-done message on the dedicated fence channel) and the
    consumer's buffer-ready signal (buf_ready_efd) completes the frame — mirroring
    how the DRM backend completes a frame on a page-flip event. This keeps KWin's
    compositing in lockstep with the frontend instead of free-running on a vsync timer.
*/
#pragma once

#include "core/backendoutput.h"

#include <QObject>
#include <memory>

namespace KWin
{

class AnlandBackend;
class AnlandEglLayer;
class OutputFrame;
class OutputLayer;
class RenderLoop;

class AnlandOutput : public BackendOutput
{
    Q_OBJECT

public:
    AnlandOutput(AnlandBackend *parent, const QString &name);
    ~AnlandOutput() override;

    RenderLoop *renderLoop() const override;
    bool testPresentation(const std::shared_ptr<OutputFrame> &frame) override;
    bool present(const QList<OutputLayer *> &layersToUpdate, const std::shared_ptr<OutputFrame> &frame) override;

    /** @p pixelSize and @p refresh (in mHz) come from the display daemon. */
    void init(const QSize &pixelSize, int refresh, qreal scale);
    void updateEnabled(bool enabled);

    /** Retune the render cycle when the consumer reports a new display refresh
     *  rate at runtime (INPUT_TYPE_DISPLAY_REFRESH). @p refresh is in mHz; values
     *  <= 0 or equal to the current rate are ignored. Updates both the RenderLoop
     *  pacing and the OutputMode so currentMode() stays consistent. */
    void setRefreshRate(int refresh);

    /** Consumer signalled buffer-ready (buf_ready_efd): complete the in-flight
     *  frame and schedule the next one, keeping the cycle consumer-paced. */
    void onConsumerReady();

    /** Reconfigure the output when the consumer uses a different buffer size
     *  (e.g. screen rotation / resolution switch). Updates the OutputMode and asks
     *  the backend to emit outputsQueried() so the Workspace re-lays-out windows
     *  for the new size — currentModeChanged() alone does not trigger a relayout. */
    void resize(const QSize &newSize);

    /** Consumer went away (fallback): fail any in-flight frame and inhibit() the
     *  RenderLoop so the compositor stops trying to render into dmabufs that no
     *  longer exist. */
    void stopRendering();

    /** Consumer reconnected: uninhibit() the RenderLoop so compositing resumes. */
    void resumeRendering();

    /** The primary render layer for this output. Owned by AnlandEglBackend and
     *  wired up in AnlandEglBackend::addOutput(); null while no render backend is
     *  attached. Lets AnlandBackend drive the layer through its output — e.g.
     *  schedule a repaint when leaving fallback. */
    void setEglLayer(AnlandEglLayer *layer);
    AnlandEglLayer *eglLayer() const;

private:
    void completeFrame();

    Q_DISABLE_COPY(AnlandOutput);

    AnlandBackend *m_backend;
    std::unique_ptr<RenderLoop> m_renderLoop;
    std::shared_ptr<OutputFrame> m_frame;
    AnlandEglLayer *m_eglLayer = nullptr;
    bool m_awaitingPresent = false;
    // Tracks our RenderLoop::inhibit() so inhibit/uninhibit stay balanced
    // (uninhibit() asserts the count is > 0). See onConsumerLost()/resumeRendering().
    bool m_renderingInhibited = false;
};

} // namespace KWin

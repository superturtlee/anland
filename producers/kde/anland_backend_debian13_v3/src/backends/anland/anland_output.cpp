/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-License-Identifier: GPL-2.0-or-later
*/
#include "anland_output.h"
#include "anland_backend.h"
#include "anland_logging.h"

#include "core/renderbackend.h" // OutputFrame
#include "core/renderloop.h"

#include <chrono>

namespace KWin
{

AnlandOutput::AnlandOutput(AnlandBackend *parent, const QString &name)
    : Output(parent)
    , m_backend(parent)
    , m_renderLoop(std::make_unique<RenderLoop>(this))
{
    setInformation(Information{
        .name = name,
        .manufacturer = QStringLiteral("anland"),
        .model = QStringLiteral("anland"),
        .internal = true,
    });
}

AnlandOutput::~AnlandOutput()
{
}

RenderLoop *AnlandOutput::renderLoop() const
{
    return m_renderLoop.get();
}

void AnlandOutput::present(const std::shared_ptr<OutputFrame> &frame)
{
    // The scene has already been rendered into the daemon's dmabuf by the layer
    // (AnlandEglLayer::doEndFrame). Hand it to the consumer now.
    m_frame = frame;
    Q_EMIT outputChange(frame->damage());

    const bool handedToConsumer = m_backend->notifyFramePresented();
    if (handedToConsumer) {
        // The consumer will present the buffer and then signal buffer-ready;
        // defer frame completion until then (see onConsumerReady()).
        m_awaitingPresent = true;
    } else {
        // Nothing was handed to the consumer this frame, so no buffer-ready will
        // arrive for it — complete it now so the RenderLoop never stalls.
        completeFrame();
    }
}

void AnlandOutput::init(const QSize &pixelSize, int refresh, qreal scale)
{
    // refresh is in mHz, like RenderLoop/OutputMode expect.
    if (refresh <= 0) {
        refresh = 120000;
    }
    m_renderLoop->setRefreshRate(refresh);

    auto mode = std::make_shared<OutputMode>(pixelSize, refresh, OutputMode::Flag::Preferred);

    setState(State{
        .position = QPoint(0, 0),
        .scale = scale,
        .modes = {mode},
        .currentMode = mode,
    });
}

void AnlandOutput::updateEnabled(bool enabled)
{
    State next = m_state;
    next.enabled = enabled;
    setState(next);
}

void AnlandOutput::setRefreshRate(int refresh)
{
    // refresh is in mHz. Ignore noise and no-op changes; RenderLoop::setRefreshRate
    // already guards the latter, but we also skip rebuilding the mode below.
    if (refresh <= 0 || refresh == m_renderLoop->refreshRate()) {
        return;
    }
    m_renderLoop->setRefreshRate(refresh);

    // Keep the OutputMode in lockstep with the RenderLoop, mirroring init(), so
    // currentMode()->refreshRate() and any mode-based logic see the new rate.
    auto mode = std::make_shared<OutputMode>(modeSize(), refresh, OutputMode::Flag::Preferred);
    State next = m_state;
    next.modes = {mode};
    next.currentMode = mode;
    setState(next);
}

void AnlandOutput::completeFrame()
{
    if (!m_frame) {
        return;
    }
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    // presented() -> RenderLoopPrivate::notifyFrameCompleted(): decrements the
    // pending-frame count, delivers presentation feedback and schedules the next
    // repaint through the standard path (the 6.x equivalent of the 5.27 backend's
    // RenderLoopPrivate::notifyFrameCompleted()).
    m_frame->presented(now, PresentationMode::VSync);
    m_frame.reset();
}

void AnlandOutput::onConsumerReady()
{
    if (m_awaitingPresent) {
        m_awaitingPresent = false;
        completeFrame();
    }
    // Render the next frame in lockstep with the consumer, exactly as the 5.27
    // backend did: the consumer's buffer-ready drives scheduleRepaint().
    m_renderLoop->scheduleRepaint();
}

void AnlandOutput::resize(const QSize &newSize)
{
    if (newSize == modeSize() || !newSize.isValid()) {
        return;
    }

    qCInfo(KWIN_ANLAND) << "resizing output to" << newSize;

    // Keep the same refresh rate; update both the OutputMode and the RenderLoop
    // pacing. Mirroring setRefreshRate() / init().
    const int refresh = m_renderLoop->refreshRate();
    auto mode = std::make_shared<OutputMode>(newSize, refresh, OutputMode::Flag::Preferred);
    State next = m_state;
    next.modes = {mode};
    next.currentMode = mode;
    setState(next);

    // setState() only emits currentModeChanged(); Workspace recomputes geometry
    // when OutputBackend::outputsQueried drives updateOutputs()/desktopResized().
    m_backend->notifyOutputsChanged();

    // Invalidate any in-flight frame: the mode just changed, so the buffer that
    // was being presented corresponds to a different layout.
    if (m_awaitingPresent) {
        m_awaitingPresent = false;
        m_frame.reset();
    }
}

void AnlandOutput::setEglLayer(AnlandEglLayer *layer)
{
    m_eglLayer = layer;
}

AnlandEglLayer *AnlandOutput::eglLayer() const
{
    return m_eglLayer;
}

void AnlandOutput::stopRendering()
{
    if (m_awaitingPresent) {
        m_awaitingPresent = false;
        // No buffer-ready will arrive for the in-flight frame now. Releasing the
        // un-presented OutputFrame makes its destructor call notifyFrameDropped(),
        // balancing the RenderLoop's pending-frame count so it does not stall
        // (the 6.x equivalent of the 5.27 backend's notifyFrameFailed()).
        m_frame.reset();
    }

    // Pause compositing while the consumer is gone: there are no dmabufs to render
    // into. The bool keeps inhibit/uninhibit balanced (uninhibit() asserts > 0).
    if (!m_renderingInhibited) {
        m_renderLoop->inhibit();
        m_renderingInhibited = true;
    }
}

void AnlandOutput::resumeRendering()
{
    // Consumer is back and dmabufs are imported: resume compositing. uninhibit()
    // reschedules the next repaint internally; the backend then marks the layer
    // dirty (scheduleRepaint) so the first frame actually paints.
    if (m_renderingInhibited) {
        m_renderLoop->uninhibit();
        m_renderingInhibited = false;
    }
}

} // namespace KWin

#include "moc_anland_output.cpp"

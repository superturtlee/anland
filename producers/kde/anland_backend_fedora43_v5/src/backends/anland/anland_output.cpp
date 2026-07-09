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
    : BackendOutput()
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

bool AnlandOutput::testPresentation(const std::shared_ptr<OutputFrame> &frame)
{
    return true;
}

bool AnlandOutput::present(const QList<OutputLayer *> &layersToUpdate, const std::shared_ptr<OutputFrame> &frame)
{
    // The scene has already been rendered into the daemon's dmabuf by the layer
    // (AnlandEglLayer::doEndFrame). Hand it to the consumer now.
    m_frame = frame;
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
    return true;
}

void AnlandOutput::init(const QSize &pixelSize, int refresh, qreal scale)
{
    // refresh is in mHz, like RenderLoop/OutputMode expect.
    if (refresh <= 0) {
        refresh = 120000;
    }
    m_renderLoop->setRefreshRate(refresh);

    auto mode = std::make_shared<OutputMode>(OutputModeline(pixelSize, refresh, OutputModeline::Flag::Preferred));

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
    auto mode = std::make_shared<OutputMode>(OutputModeline(modeSize(), refresh, OutputModeline::Flag::Preferred));
    State next = m_state;
    next.modes = QList<std::shared_ptr<OutputMode>>{mode};
    next.currentMode = mode;
    setState(next);
}

void AnlandOutput::completeFrame()
{
    if (!m_frame) {
        return;
    }
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
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
    if (newSize == modeSize() || !newSize.isValid())
        return;

    qCInfo(KWIN_ANLAND) << "resizing output to" << newSize;

    // Keep the same refresh rate; update both the OutputMode and the RenderLoop
    // pacing. Mirroring setRefreshRate() / init().
    const int refresh = m_renderLoop->refreshRate();
    auto mode = std::make_shared<OutputMode>(OutputModeline(newSize, refresh, OutputModeline::Flag::Preferred));
    State next = m_state;
    next.modes = QList<std::shared_ptr<OutputMode>>{mode};
    next.currentMode = mode;
    setState(next);

    // setState() only emits currentModeChanged(); that does NOT re-lay-out windows.
    // The Workspace recomputes geometry in updateOutputs()/desktopResized(), which is
    // driven by OutputBackend::outputsQueried (see Workspace ctor). Since we changed
    // the mode directly here rather than through OutputConfiguration, emit it now so
    // the compositor recalculates the layout for the new size.
    m_backend->notifyOutputsChanged();

    // Invalidate any in-flight frame: the mode just changed, so the buffer that was
    // being presented corresponds to a different layout.
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
        m_frame.reset();
    }

    if (!m_renderingInhibited) {
        m_renderLoop->inhibit();
        m_renderingInhibited = true;
    }
}

void AnlandOutput::resumeRendering()
{
    if (m_renderingInhibited) {
        m_renderLoop->uninhibit();
        m_renderingInhibited = false;
    }
}

} // namespace KWin

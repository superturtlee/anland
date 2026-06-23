/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-License-Identifier: GPL-2.0-or-later

    Native KWin output+input backend that talks to the Android display daemon
    directly (via libdisplay_producer), instead of running nested inside the
    weston "anland" compositor. Port of weston/libweston/backend-anland/anland.c
    to KWin's OutputBackend architecture.
*/
#pragma once

#include "core/outputbackend.h"

#include <QByteArray>
#include <QPointF>
#include <QVector>
#include <memory>

extern "C" {
#include "display_producer.h"
#include "protocol.h"
}

class QSocketNotifier;
class QTimer;

namespace KWin
{

class AnlandOutput;
class AnlandInputDevice;
class AbstractDataSource;
class DrmDevice;
class EglDisplay;
class InputBackend;
class OpenGLBackend;

class KWIN_EXPORT AnlandBackend : public OutputBackend
{
    Q_OBJECT

public:
    explicit AnlandBackend(const QString &socketPath = QString(), QObject *parent = nullptr);
    ~AnlandBackend() override;

    bool initialize() override;

    std::unique_ptr<OpenGLBackend> createOpenGLBackend() override;
    std::unique_ptr<InputBackend> createInputBackend() override;
    QList<CompositingType> supportedCompositors() const override;
    Outputs outputs() const override;

    // 6.x stores the scene EGL display on the output backend; the EGL render
    // backend creates it (surfaceless) and hands it over via setEglDisplay().
    EglDisplay *sceneEglDisplayObject() const override;
    void setEglDisplay(std::unique_ptr<EglDisplay> &&display);

    display_ctx *display() const
    {
        return m_display;
    }
    /**
     * DRM render device backing GL/EGL. KWin dereferences this during OpenGL
     * compositor setup (syncobj-timeline / dmabuf-feedback probing), so it must
     * be non-null; AnlandEglBackend::drmDevice() forwards to it.
     */
    DrmDevice *drmDevice() const
    {
        return m_drmDevice.get();
    }
    AnlandInputDevice *inputDevice() const
    {
        return m_inputDevice.get();
    }

    /**
     * Called by AnlandEglBackend::present(): hand the freshly painted buffer to
     * the consumer (signals the render-done fence channel). Returns true if the buffer was
     * actually handed over (consumer was ready), false otherwise — the caller
     * uses this to decide how to complete the RenderLoop frame.
     */
    bool notifyFramePresented();

    /** Re-run the Workspace output layout after an output changed its mode at
     *  runtime (AnlandOutput::resize). The backend mutates the mode directly via
     *  setState() instead of going through OutputConfiguration, so it must emit
     *  outputsQueried() itself; the mode-changed signal alone does not trigger a
     *  relayout. */
    void notifyOutputsChanged()
    {
        Q_EMIT outputsQueried();
    }

private:
    void setupNotifiers();
    void teardownNotifiers();
    void onInputReadable();
    void onBufferReady();
    void processInputEvent(const InputEvent &ev);
    QPointF mapInputToLogical(const QPointF &devicePoint) const;
    void onReconnectTimer();
    void enterFallback();

    void onClipboardChanged();
    void sendClipboardToConsumer(const QByteArray &text);
    void sendClipboardToKWin(const QByteArray &text);

    static void fallbackTrampoline(void *data);

    QString m_socketPath;
    display_ctx *m_display = nullptr;

    std::unique_ptr<DrmDevice> m_drmDevice;
    std::unique_ptr<EglDisplay> m_eglDisplay;
    QVector<AnlandOutput *> m_outputs;
    std::unique_ptr<AnlandInputDevice> m_inputDevice;

    QSocketNotifier *m_inputNotifier = nullptr;
    QSocketNotifier *m_bufReadyNotifier = nullptr;
    QTimer *m_reconnectTimer = nullptr;

    bool m_consumerReady = false;
    bool m_inFallback = false;
    QByteArray m_clipboardText;
    std::unique_ptr<AbstractDataSource> m_clipboardSource;
};

} // namespace KWin

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
#include "core/renderdevice.h"

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
class BackendOutput;
class DrmDevice;
class EglBackend;
class EglDisplay;
class InputBackend;
class RenderDevice;

class KWIN_EXPORT AnlandBackend : public OutputBackend
{
    Q_OBJECT

public:
    explicit AnlandBackend(const QString &socketPath = QString(), QObject *parent = nullptr);
    ~AnlandBackend() override;

    bool initialize() override;

    std::unique_ptr<EglBackend> createOpenGLBackend() override;
    std::unique_ptr<InputBackend> createInputBackend() override;
    QList<CompositingType> supportedCompositors() const override;
    QList<BackendOutput *> outputs() const override;

    EglDisplay *sceneEglDisplayObject() const override;
    RenderDevice *renderDevice() const;

    display_ctx *display() const
    {
        return m_display;
    }
    DrmDevice *drmDevice() const
    {
        return m_renderDevice ? m_renderDevice->drmDevice() : nullptr;
    }
    AnlandInputDevice *inputDevice() const
    {
        return m_inputDevice.get();
    }

    bool notifyFramePresented();

    /** Re-run the Workspace output layout after an output changed its mode at
     *  runtime (AnlandOutput::resize). The backend mutates the mode directly via
     *  setState() instead of going through OutputConfiguration, so — exactly like
     *  DrmBackend/VirtualBackend do after altering their output set — it must emit
     *  outputsQueried() itself. Otherwise Workspace::updateOutputs() never runs and
     *  windows keep their old geometry (the mode-changed signal alone does not
     *  trigger a relayout). */
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

    // Clipboard sync — bidirectional bridge between KWin selection / consumer
    void onClipboardChanged();
    void sendClipboardToConsumer(const QByteArray &text);
    void sendClipboardToKWin(const QByteArray &text);

    // Inject UTF-8 text from the consumer's IME into the focused KWin client.
    void sendTextInputToKWin(const QByteArray &text);

    static void fallbackTrampoline(void *data);

    QString m_socketPath;
    display_ctx *m_display = nullptr;

    std::unique_ptr<RenderDevice> m_renderDevice;
    QVector<AnlandOutput *> m_outputs;
    std::unique_ptr<AnlandInputDevice> m_inputDevice;

    QSocketNotifier *m_inputNotifier = nullptr;
    QSocketNotifier *m_bufReadyNotifier = nullptr;
    QTimer *m_reconnectTimer = nullptr;

    bool m_consumerReady = false;
    bool m_inFallback = false;

    // Last known clipboard text — used to de-duplicate (KWin changed -> we sent ->
    // consumer sets the same text on Android -> consumer sends back to KWin).
    // QByteArray is trivially sent over the data channel as UTF-8.
    QByteArray m_clipboardText;
};

} // namespace KWin

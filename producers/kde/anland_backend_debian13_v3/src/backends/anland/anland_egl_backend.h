/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-License-Identifier: GPL-2.0-or-later

    OpenGL/EGL render backend for the anland backend. It renders the KWin scene
    directly into the dmabuf buffers provided by the display daemon (imported by
    fd) and tells the daemon to present them. Modeled on VirtualEglBackend, with
    the render target being one of the daemon's dmabufs instead of an internal
    FBO. The consumer rotates the buffer index externally (shared memory), so the
    layer keeps per-buffer accumulated damage (buffer-age equivalent), exactly
    like weston's backend-anland.
*/
#pragma once

#include "core/outputlayer.h"
#include "platformsupport/scenes/opengl/abstract_egl_backend.h"

#include <QRegion>
#include <array>
#include <map>
#include <memory>

extern "C" {
#include "display_producer.h"
#include "protocol.h"
}

namespace KWin
{
class GLFramebuffer;
class GLTexture;
class DrmDevice;
class OutputFrame;
class Output;
class SurfacePixmap;
class SurfaceTexture;
class AnlandBackend;
class AnlandEglBackend;
class AnlandOutput;

class AnlandEglLayer : public OutputLayer
{
public:
    AnlandEglLayer(AnlandOutput *output, AnlandEglBackend *backend);
    ~AnlandEglLayer() override;

    std::optional<OutputLayerBeginFrameInfo> doBeginFrame() override;
    bool doEndFrame(const QRegion &renderedDeviceRegion, const QRegion &damagedDeviceRegion, OutputFrame *frame) override;

    // OutputLayer pure virtuals. anland imports the consumer's dmabufs and
    // never allocates or scans out through DRM itself, so these are stubs.
    DrmDevice *scanoutDevice() const override;
    QHash<uint32_t, QList<uint64_t>> supportedDrmFormats() const override;

    // Buffer lifetime is driven by the backend state machine, not doBeginFrame():
    // importBuffers() imports the daemon's dmabuf set on (re)connect and arms a
    // full-output (infinite) repaint on every rotation buffer; releaseBuffers()
    // drops them on fallback. Both make
    // the GL context current, so they are safe to call from outside a frame.
    bool importBuffers(int count);
    void releaseBuffers();
    std::shared_ptr<GLTexture> texture() const;

private:
    void onOutputTransformChanged();

    AnlandEglBackend *const m_backend;
    AnlandOutput *m_output;
    // Cached producer handle (stable for the backend's lifetime); avoids the
    // m_backend->display() forwarding chain on every doBeginFrame().
    display_ctx *const m_display;

    int m_bufCount = 0;
    int m_currentIndex = 0;
    std::array<std::shared_ptr<GLTexture>, MAX_BUFS> m_textures;
    std::array<std::unique_ptr<GLFramebuffer>, MAX_BUFS> m_fbos;
    /* Per-buffer accumulated damage: each buffer must remember everything that
     * changed since it was last rendered, because the consumer rotates the
     * selected index out from under us. */
    std::array<QRegion, MAX_BUFS> m_accumDamage;
};

class AnlandEglBackend : public AbstractEglBackend
{
    Q_OBJECT

public:
    AnlandEglBackend(AnlandBackend *b);
    ~AnlandEglBackend() override;

    void init() override;
    std::unique_ptr<SurfaceTexture> createSurfaceTextureWayland(SurfacePixmap *pixmap) override;
    std::pair<std::shared_ptr<KWin::GLTexture>, ColorDescription> textureForOutput(Output *output) const override;
    OutputLayer *primaryLayer(Output *output) override;
    bool present(Output *output, const std::shared_ptr<OutputFrame> &frame) override;
    DrmDevice *drmDevice() const override;

    AnlandBackend *backend() const
    {
        return m_backend;
    }
    display_ctx *display() const;

private:
    bool initializeEgl();
    bool initRenderingContext();

    void addOutput(Output *output);
    void removeOutput(Output *output);

    AnlandBackend *m_backend;
    std::map<Output *, std::unique_ptr<AnlandEglLayer>> m_outputs;
};

} // namespace KWin

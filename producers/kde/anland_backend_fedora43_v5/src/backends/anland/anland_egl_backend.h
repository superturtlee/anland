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
#include "core/region.h"
#include "opengl/eglbackend.h"

#include <array>
#include <map>
#include <memory>
#include <optional>

extern "C" {
#include "display_producer.h"
#include "protocol.h"
}

namespace KWin
{
class GLFramebuffer;
class GLTexture;
class BackendOutput;
class DrmDevice;
class OutputFrame;
class AnlandBackend;
class AnlandEglBackend;
class AnlandOutput;

class AnlandEglLayer : public OutputLayer
{
public:
    AnlandEglLayer(AnlandOutput *output, AnlandEglBackend *backend);
    ~AnlandEglLayer() override;

    std::optional<OutputLayerBeginFrameInfo> doBeginFrame() override;
    bool doEndFrame(const Region &renderedDeviceRegion, const Region &damagedDeviceRegion, OutputFrame *frame) override;
    DrmDevice *scanoutDevice() const override;
    FormatModifierMap supportedDrmFormats() const override;
    bool importBuffers(int count);
    void releaseBuffers() override;

private:
    void onOutputTransformChanged();

    AnlandEglBackend *const m_backend;
    AnlandOutput *m_output;
    display_ctx *const m_display;

    int m_bufCount = 0;
    int m_currentIndex = 0;
    uint8_t m_damageFlags = 0;
    uint8_t m_damageMask = 0;
    std::array<std::shared_ptr<GLTexture>, MAX_BUFS> m_textures;
    std::array<std::unique_ptr<GLFramebuffer>, MAX_BUFS> m_fbos;
    std::array<std::optional<RenderTarget>, MAX_BUFS> m_renderTargets;
    std::array<Region, MAX_BUFS> m_accumDamage;
};

class AnlandEglBackend : public EglBackend
{
    Q_OBJECT

public:
    AnlandEglBackend(AnlandBackend *b);
    ~AnlandEglBackend() override;

    bool init() override;
    QList<OutputLayer *> compatibleOutputLayers(BackendOutput *output) override;
    DrmDevice *drmDevice() const override;

    AnlandBackend *backend() const
    {
        return m_backend;
    }
    display_ctx *display() const;

private:
    bool initializeEgl();
    bool initRenderingContext();

    void addOutput(BackendOutput *output);
    void removeOutput(BackendOutput *output);

    AnlandBackend *m_backend;
    std::map<BackendOutput *, std::unique_ptr<AnlandEglLayer>> m_outputs;
};

} // namespace KWin

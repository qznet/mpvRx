/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.graphics.PixelFormat
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowInsetsCompat
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.hdr.HdrToysManager
import app.gyrolet.mpvrx.network.AndroidCookieJar
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.DEFAULT_SUBTITLE_FONT_FAMILY
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.ui.player.PlayerActivity.Companion.TAG
import app.gyrolet.mpvrx.ui.player.anime4k.applyAnime4KShaderChain
import app.gyrolet.mpvrx.ui.player.anime4k.applyAnime4KStabilityOptions
import app.gyrolet.mpvrx.ui.player.anime4k.clearAnime4KShaders
import app.gyrolet.mpvrx.ui.player.anime4k.selectRuntimeStableAnime4K
import app.gyrolet.mpvrx.ui.player.controls.components.panels.toColorHexString
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
import app.gyrolet.mpvrx.utils.media.VideoCodecSupportInspector
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPVLib
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.reflect.KProperty

private fun String.toMpvLanguageList(): String =
  split(',').map(String::trim).filter(String::isNotEmpty).joinToString(",")

class MPVView(
  context: Context,
  attributes: AttributeSet,
) : BaseMPVView(context, attributes),
  KoinComponent {
  private val audioPreferences: AudioPreferences by inject()
  private val playerPreferences: PlayerPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val mpvConfigCache: MpvConfigCache by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val ytdlPreferences: YtdlPreferences by inject()
  private val anime4kManager: Anime4KManager by inject()
  private val hdrToysManager: HdrToysManager by inject()

  var isExiting = false
  var forceOpenGlFallback = false
  var isSurfaceReady = false
    private set
  var onSurfaceReady: (() -> Unit)? = null
  var surfaceBindingEnabled = true
    set(value) {
      field = value
      if (!value) isSurfaceReady = false
    }

  /**
   * Configures the process-wide player and binds this view as its current rendering surface.
   * Re-entering the player reuses the live core; it never creates a second native instance.
   */
  fun initializeSession(
    configDir: String,
    cacheDir: String,
  ): Result<Boolean> {
    // The libmpv core is process-wide, so returning to the player can reuse a core created with
    // older renderer preferences. Keep fallbacks stable for the lifetime of that preference
    // selection, but recreate the core when gpu-next/Vulkan selection actually changes.
    MpvConfigOverridePolicy.configure(advancedPreferences.mpvConfOverrides.get())
    val requestedBackend = selectRenderBackend(ignoreForcedOpenGlFallback = true)
    val scriptsKey = advancedPreferences.userScriptsConfigurationKey()
    val coreConfigurationKey =
      "${requestedBackend.configurationKey}|conf=${MpvConfigOverridePolicy.configurationKey()}" +
        "|mpv=${mpvConfigCache.configurationKey()}|scripts=$scriptsKey"
    val result =
      PlaybackSession.initialize(
        context = context.applicationContext,
        configDir = configDir,
        cacheDir = cacheDir,
        coreConfigurationKey = coreConfigurationKey,
        initOptions = ::initOptions,
        postInitOptions = ::postInitOptions,
        observeProperties = ::observeProperties,
        userScriptsKey = scriptsKey,
      )
    if (result.isSuccess) {
      holder.removeCallback(this)
      holder.addCallback(this)
      if (isMediaCodecEmbedActive()) {
        syncOsdSurfaceAttachment()
      } else {
        // The renderer may have been switched away from the embedded output since the last
        // session; drop any leftover embed letterbox so the new output gets the full viewport.
        restoreFullSizeEmbedBounds()
      }
      if (holder.surface.isValid && !isSurfaceReady) surfaceCreated(holder)
    }
    return result
  }

  internal fun userScriptsNeedReload(): Boolean =
    PlaybackSession.userScriptsNeedReload(advancedPreferences.userScriptsConfigurationKey())

  fun releaseSurface() {
    holder.removeCallback(this)
    unregisterEmbedRelayoutListener()
    if (isSurfaceReady || PlaybackSession.state.value.surfaceAttached) {
      isSurfaceReady = false
      PlaybackSession.unbindSurface(this)
    }
  }

  fun rebindCurrentSurface() {
    if (!surfaceBindingEnabled || !holder.surface.isValid) return
    isSurfaceReady = false
    surfaceCreated(holder)
  }

  // ==================== MediaCodec embed (HW+) OSD window ====================

  private var osdSurfaceView: SurfaceView? = null
  private var osdSurfaceReady = false
  private var osdSurfaceAttached = false

  /**
   * Wires the second SurfaceView that vo=mediacodec_embed renders its OSD/subtitles into.
   *
   * MediaCodec hands the decoded frame straight to the player surface, so mpv has no GL pass left
   * to draw the OSD into and needs its own window (`android-osd-wid`). Without it mpv aborts the
   * video output with "No Android OSD Surface is attached for direct MediaCodec output" and keeps
   * playing audio over a black screen — the exact symptom of the embed toggle in settings.
   *
   * Only ever attaches while the embedded video output is active, so gpu/gpu-next playback is
   * completely unaffected.
   */
  fun setOsdSurfaceView(surfaceView: SurfaceView) {
    if (osdSurfaceView === surfaceView) return
    osdSurfaceView = surfaceView
    // Transparent + media overlay: an opaque OSD surface would cover the video (audio only), and
    // without the overlay z-order it would sit behind it.
    surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)
    surfaceView.setZOrderMediaOverlay(true)
    surfaceView.holder.addCallback(
      object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
          osdSurfaceReady = true
          syncOsdSurfaceAttachment()
        }

        override fun surfaceChanged(
          holder: SurfaceHolder,
          format: Int,
          width: Int,
          height: Int,
        ) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
          osdSurfaceReady = false
          if (!osdSurfaceAttached) return
          osdSurfaceAttached = false
          runCatching { MPVLib.detachOsdSurface() }
            .onFailure { Log.w(TAG, "Failed to detach OSD surface", it) }
        }
      },
    )
  }

  /** Attaches/detaches the OSD window to match the currently active video output. */
  fun syncOsdSurfaceAttachment() {
    val view = osdSurfaceView ?: return
    if (!isMediaCodecEmbedActive()) {
      if (!osdSurfaceAttached) return
      osdSurfaceAttached = false
      runCatching { MPVLib.detachOsdSurface() }
      return
    }
    if (osdSurfaceAttached || !osdSurfaceReady) return
    val surface = view.holder.surface.takeIf { it.isValid } ?: return
    runCatching { MPVLib.attachOsdSurface(surface) }
      .onSuccess {
        osdSurfaceAttached = true
        // The VO may have been opened before the OSD window existed, in which case mpv gave up on
        // the video track; reopen it so the picture actually comes back.
        reopenEmbedVideoOutput()
      }
      .onFailure { Log.w(TAG, "Failed to attach OSD surface", it) }
  }

  /**
   * Re-opens the embedded video output so it picks up the OSD window.
   *
   * Setting `vo` to the value it already holds is a no-op, so it is switched through "null" first.
   * When the open fails mpv deselects the video track entirely and never retries, so re-select it
   * as well — otherwise the picture stays black until the next file is loaded.
   */
  private fun reopenEmbedVideoOutput() {
    if (!isMediaCodecEmbedActive()) return
    runCatching {
      MPVLib.setPropertyString("vo", "null")
      MPVLib.setPropertyString("vo", "mediacodec_embed")
    }.onFailure { Log.w(TAG, "Failed to reopen embedded video output", it) }
    if (runCatching { MPVLib.getPropertyInt("video-params/w") }.getOrNull() == null) {
      runCatching { MPVLib.setPropertyString("vid", "auto") }
    }
  }

  /**
   * Public safety net: bring the picture back if vo=mediacodec_embed never came up.
   *
   * Mirrors BaseMPVView.recoverVideoOutputIfNeeded(). The embed VO needs the OSD ANativeWindow at
   * the moment it opens; if that window is not present (or the video surface binds after the OSD
   * window) mpv aborts the open and deselects the video track, leaving audio over a black screen.
   * This is a no-op whenever the video is already running, and waits for the OSD surface if it has
   * not appeared yet (the OSD callback retries as soon as it shows up).
   */
  fun recoverVideoOutputIfNeeded() {
    if (runCatching { MPVLib.getPropertyInt("video-params/w") }.getOrNull() != null) return
    // Nothing can be done until the OSD surface exists.
    if (isMediaCodecEmbedActive() && osdSurfaceView != null && !osdSurfaceReady) return
    if (!isMediaCodecEmbedActive()) return
    Log.w(TAG, "embed video output missing, re-opening vo=mediacodec_embed")
    reopenEmbedVideoOutput()
  }

  private var lastEmbedAspect: Double? = null

  /**
   * Letterboxes vo=mediacodec_embed at the view level.
   *
   * MediaCodec writes the frame directly into this SurfaceView's ANativeWindow, so mpv cannot scale
   * or letterbox it the way gpu/gpu-next do internally. Shrinking the view to a centered rectangle
   * that matches the source DAR lets the black parent show through as letterbox/pillarbox.
   * No-op for every other video output.
   */
  fun applyEmbedAspectRatio(aspect: Double?) {
    if (!isMediaCodecEmbedActive()) return
    if (aspect != null && aspect > 0.0) lastEmbedAspect = aspect
    applyEmbedAspectRatioBounds()
  }

  private fun applyEmbedAspectRatioBounds() {
    if (!isMediaCodecEmbedActive()) return
    val dar = lastEmbedAspect ?: return
    val container = parent as? ViewGroup ?: return
    val containerWidth = container.width
    val containerHeight = container.height
    if (containerWidth <= 0 || containerHeight <= 0) return
    val containerDar = containerWidth.toDouble() / containerHeight.toDouble()
    val videoWidth: Int
    val videoHeight: Int
    if (dar > containerDar) {
      videoWidth = containerWidth
      videoHeight = (containerWidth / dar).toInt()
    } else {
      videoWidth = (containerHeight * dar).toInt()
      videoHeight = containerHeight
    }
    val params = layoutParams as? ConstraintLayout.LayoutParams ?: return
    if (params.width == videoWidth && params.height == videoHeight) return
    Log.i(TAG, "MediaCodec embed letterbox: dar=$dar container=${containerWidth}x$containerHeight -> ${videoWidth}x$videoHeight")
    params.width = videoWidth
    params.height = videoHeight
    params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
    params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
    params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
    params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
    params.horizontalBias = 0.5f
    params.verticalBias = 0.5f
    layoutParams = params
  }

  /** Drops the embed letterbox from a previous session so gpu/gpu-next gets the full viewport back. */
  private fun restoreFullSizeEmbedBounds() {
    lastEmbedAspect = null
    val params = layoutParams as? ConstraintLayout.LayoutParams ?: return
    if (params.width == ViewGroup.LayoutParams.MATCH_PARENT &&
      params.height == ViewGroup.LayoutParams.MATCH_PARENT
    ) {
      return
    }
    params.width = ViewGroup.LayoutParams.MATCH_PARENT
    params.height = ViewGroup.LayoutParams.MATCH_PARENT
    layoutParams = params
  }

  /**
   * The aspect callback that drives [applyEmbedAspectRatioBounds] can arrive while the parent is
   * still mid-layout, and no further aspect event comes for the same file — so recompute against
   * the settled container size as well. Cheap: it bails out unless the rectangle really changes.
   */
  private val embedRelayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
    if (!isMediaCodecEmbedActive() || lastEmbedAspect == null) return@OnGlobalLayoutListener
    // User-picked Crop (panscan) / Stretch / custom aspect must not be fought.
    val override = PlaybackSession.getPropertyDouble("video-aspect-override") ?: -1.0
    val panscan = PlaybackSession.getPropertyDouble("panscan") ?: 0.0
    if (override > 0.0 || panscan >= 1.0) return@OnGlobalLayoutListener
    applyEmbedAspectRatioBounds()
  }

  private var embedRelayoutListenerRegistered = false

  private fun registerEmbedRelayoutListener() {
    if (embedRelayoutListenerRegistered || !isMediaCodecEmbedActive()) return
    if (!viewTreeObserver.isAlive) return
    embedRelayoutListenerRegistered = true
    viewTreeObserver.addOnGlobalLayoutListener(embedRelayoutListener)
  }

  private fun unregisterEmbedRelayoutListener() {
    if (!embedRelayoutListenerRegistered) return
    embedRelayoutListenerRegistered = false
    if (viewTreeObserver.isAlive) {
      runCatching { viewTreeObserver.removeOnGlobalLayoutListener(embedRelayoutListener) }
    }
  }

  private data class RenderBackendSelection(
    val vo: String,
    val gpuApi: String,
    val gpuContext: String,
    val reason: String,
  ) {
    val configurationKey: String
      get() = "$vo|$gpuApi|$gpuContext"
  }

  fun getVideoOutAspect(): Double? {
    // Try to get aspect from video-params/aspect first
    val rawAspect = PlaybackSession.getPropertyDouble("video-params/aspect")
    val rotate = PlaybackSession.getPropertyInt("video-params/rotate") ?: 0

    // If aspect is not available or 0, calculate from width and height
    val finalAspect =
      if (rawAspect == null || rawAspect < 0.001) {
        val width =
          runCatching {
            PlaybackSession.getPropertyInt("width") ?: PlaybackSession.getPropertyInt("video-params/w") ?: 0
          }.getOrDefault(0)

        val height =
          runCatching {
            PlaybackSession.getPropertyInt("height") ?: PlaybackSession.getPropertyInt("video-params/h") ?: 0
          }.getOrDefault(0)

        if (width > 0 && height > 0) {
          width.toDouble() / height.toDouble()
        } else {
          null
        }
      } else {
        rawAspect
      }

    return finalAspect?.let { aspect ->
      if (aspect <= 0.001) {
        return null
      }
      val isRotated = (rotate % 180 == 90)
      val correctedAspect = if (isRotated) 1.0 / aspect else aspect
      correctedAspect
    }
  }

  class TrackDelegate(
    private val name: String,
  ) {
    operator fun getValue(
      thisRef: Any?,
      property: KProperty<*>,
    ): Int {
      val v = PlaybackSession.getPropertyString(name)
      // we can get null here for "no" or other invalid value
      return v?.toIntOrNull() ?: -1
    }

    operator fun setValue(
      thisRef: Any?,
      property: KProperty<*>,
      value: Int,
    ) {
      if (value == -1) {
        PlaybackSession.setPropertyString(name, "no")
      } else {
        PlaybackSession.setPropertyString(name, value.toString())
      }
    }
  }

  var sid: Int by TrackDelegate("sid")
  var secondarySid: Int by TrackDelegate("secondary-sid")
  var aid: Int by TrackDelegate("aid")

  /**
   * Render via MediaCodec's embedded (zero-copy) surface when either the dedicated
   * preference is enabled or the user set `vo=mediacodec_embed` (or `video-output=...`) in mpv.conf.
   * This path must NOT emit gpu-api / gpu-context — they are incompatible with
   * vo=mediacodec_embed and previously caused a black-screen-with-audio regression.
   */
  private fun isMediaCodecEmbedActive(): Boolean {
    if (decoderPreferences.useMediaCodecEmbed.get()) return true
    return advancedPreferences.mpvConf
      .get()
      .lineSequence()
      .any { raw ->
        val line = raw.substringBefore('#').trim()
        line.equals("vo=mediacodec_embed", ignoreCase = true) ||
          line.equals("video-output=mediacodec_embed", ignoreCase = true)
      }
  }

  override fun initOptions() {
    val profile = decoderPreferences.profile.get()
    PlaybackSession.setOptionString("profile", profile)

    val useMediaCodecEmbed = isMediaCodecEmbedActive()
    val backend =
      if (useMediaCodecEmbed) {
        RenderBackendSelection(
          vo = "mediacodec_embed",
          gpuApi = "",
          gpuContext = "",
          reason = "MediaCodec Embed (zero-copy) video output requested",
        )
      } else {
        selectRenderBackend()
      }
    val useVulkan = backend.gpuApi == "vulkan"
    val hwdecMode = preferredHwdecMode(useVulkan)
    PlaybackSession.setVideoOutput(backend.vo)
    if (!useMediaCodecEmbed) {
      // gpu-api / gpu-context are incompatible with vo=mediacodec_embed; omit them there.
      PlaybackSession.setOptionString("gpu-api", backend.gpuApi)
      PlaybackSession.setOptionString("gpu-context", backend.gpuContext)
    }

    val hdrScreenOutputEnabled = decoderPreferences.hdrScreenOutput.get()
    val isLinearAvailable = useVulkan && backend.vo == "gpu-next"
    val hdrScreenMode =
      if (!hdrScreenOutputEnabled) {
        HdrScreenMode.OFF
      } else {
        val mode = decoderPreferences.hdrScreenMode.get()
        if (mode == HdrScreenMode.LINEAR && !isLinearAvailable) {
          HdrScreenMode.defaultEnabledMode
        } else {
          mode
        }
      }
    val hdrPipelineReady = hdrScreenMode != HdrScreenMode.LINEAR || isLinearAvailable
    if (!useMediaCodecEmbed && !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
      applyHdrScreenOutputOptions(
        mode = hdrScreenMode,
        pipelineReady = hdrPipelineReady,
        boostSdrToHdr = decoderPreferences.boostSdrToHdr.get(),
      )
    }

    // Fongmi can map direct MediaCodec frames into Vulkan; other Vulkan builds start with copy mode.
    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HARDWARE_DECODER)) {
      val hardwareDecoderCodecs = VideoCodecSupportInspector.hardwareDecoderCodecIds()
      val effectiveHwdec =
        if (useMediaCodecEmbed) {
          // Embedded MediaCodec surface requires direct (non-copy) HW decoding.
          if (decoderPreferences.tryHWDecoding.get() && hardwareDecoderCodecs.isNotEmpty()) "mediacodec" else "no"
        } else if (hardwareDecoderCodecs.isEmpty()) {
          "no"
        } else {
          hwdecMode
        }
      PlaybackSession.setOptionString("hwdec", effectiveHwdec)
      if (hardwareDecoderCodecs.isNotEmpty()) {
        PlaybackSession.setOptionString("hwdec-codecs", hardwareDecoderCodecs.joinToString(","))
      }
    }

    // These were forced on between the last known-good build (e3b1de8) and the first build
    // reproducing the HEVC/Main10 frame-drop regression (84f21fc). Keep mpv's normal direct-
    // rendering heuristic and disable the extra decoder-frame queue, matching mpv's defaults.
    PlaybackSession.setOptionString("vd-lavc-dr", "auto")
    PlaybackSession.setOptionString("vd-lavc-queue", "no")

    if (!useMediaCodecEmbed && decoderPreferences.useYUV420P.get()) {
      PlaybackSession.setOptionString("vf", "format=yuv420p")
    }
    val logLevel = if (advancedPreferences.verboseLogging.get()) "v" else "warn"
    PlaybackSession.setOptionString("msg-level", "all=$logLevel")

    PlaybackSession.setOptionString("keep-open", "yes")
    PlaybackSession.setOptionString("input-default-bindings", "yes")

    PlaybackSession.setOptionString("tls-verify", "yes")
    PlaybackSession.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")

    val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    screenshotDir.mkdirs()
    PlaybackSession.setOptionString("screenshot-directory", screenshotDir.path)

    VideoFilters.entries.forEach {
      PlaybackSession.setOptionString(it.mpvProperty, it.preference(decoderPreferences).get().toString())
    }

    PlaybackSession.setOptionString("speed", playerPreferences.defaultSpeed.get().toString())
    // Avoid forcing CPU-side film-grain synthesis globally; this can spike thermals on mobile SoCs.
    // Let mpv choose the safest path for the active decoder/backend.
    PlaybackSession.setOptionString("vd-lavc-film-grain", "auto")

    // Streaming improvements
    // Use adaptive HLS bitrate selection to avoid forcing the heaviest stream profile.
    // This reduces thermal load and helps prevent jitter/rebuffering on long sessions.
    PlaybackSession.setOptionString("hls-bitrate", "no")
    PlaybackSession.setOptionString("http-allow-redirect", "yes")
    PlaybackSession.setOptionString("cookies", "yes")
    PlaybackSession.setOptionString("cookies-file", AndroidCookieJar.playbackCookieFile(context).absolutePath)
    PlaybackSession.setOptionString("cache", "auto")
    PlaybackSession.setOptionString("cache-pause", "yes")
    PlaybackSession.setOptionString("cache-pause-wait", "2")
    PlaybackSession.setOptionString("demuxer-max-bytes", "64MiB")
    // Recover boundedly from transient HTTP/TLS disconnects, including non-seekable live inputs.
    // Do not use reconnect_at_eof globally: a legitimate VOD EOF must still finish normally.
    PlaybackSession.setOptionString(
      "demuxer-lavf-o",
      "http_persistent=0,reconnect=1,reconnect_on_network_error=1,reconnect_streamed=1," +
        "reconnect_delay_max=5,reconnect_max_retries=5,reconnect_delay_total_max=20",
    )
    // demuxer-lavf-o only reaches demuxer-internal opens (HLS/DASH segments). The primary http(s)
    // URL is opened by stream_lavf, which reads stream-lavf-o; without it a dropped connection or
    // one failed seek-reopen permanently stalls network playback (endless buffering).
    PlaybackSession.setOptionString(
      "stream-lavf-o",
      "reconnect=1,reconnect_on_network_error=1,reconnect_on_http_error=5xx,reconnect_streamed=1," +
        "reconnect_delay_max=5,reconnect_max_retries=5,reconnect_delay_total_max=20",
    )
    // Drop only video-output-bound late frames when rendering cannot keep up.
    // This prevents long-term jitter buildup without aggressively sacrificing smoothness.
    PlaybackSession.setOptionString("framedrop", "vo")

    val preciseSeek = playerPreferences.usePreciseSeeking.get()
    PlaybackSession.setOptionString("hr-seek", if (preciseSeek) "yes" else "no")
    PlaybackSession.setOptionString("hr-seek-framedrop", if (preciseSeek) "no" else "yes")

    // Use audio-based video sync for better frame pacing with 4K HDR content.
    // This prevents timing jitter when the display refresh rate doesn't perfectly
    // match the video frame rate (e.g., 24fps content on 60Hz display).
    PlaybackSession.setOptionString("video-sync", "audio")

    // Anime4K shader initialization (MUST be in initOptions, not after file load!)
    if (!useMediaCodecEmbed && !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) {
      applyAnime4KShaders(backend.vo, backend.gpuApi)
    }
    // HDR Toys shaders (loaded after Anime4K so they append in the correct order)
    if (!useMediaCodecEmbed && !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
      applyHdrToysMode(hdrScreenMode, hdrPipelineReady)
    }

    setupSubtitlesOptions()
    setupAudioOptions()
    YtdlpManager.setupMpvOptions(context, ytdlPreferences, subtitlesPreferences)
  }

  override fun observeProperties() {
    for ((name, format) in observedProps) PlaybackSession.observeProperty(name, format)
  }

  override fun postInitOptions() {
    applyOsdSafeAreaMargins()

    when (decoderPreferences.debanding.get()) {
      Debanding.None -> {}
      Debanding.CPU -> PlaybackSession.command("vf", "add", "@deband:gradfun=radius=12")
      Debanding.GPU -> if (!isMediaCodecEmbedActive()) PlaybackSession.setOptionString("deband", "yes")
    }

    advancedPreferences.enabledStatisticsPage.get().let {
      if (it in 1..5) {
        PlaybackSession.command("script-binding", "stats/display-stats-toggle")
        PlaybackSession.command("script-binding", "stats/display-page-$it")
      }
    }
  }

  fun applyOsdSafeAreaMargins(insets: WindowInsetsCompat? = null) {
    val resolvedInsets =
      insets ?: androidx.core.view.ViewCompat
        .getRootWindowInsets(this)
    val cutoutInsets = resolvedInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
    val horizontalMargin = maxOf(cutoutInsets?.left ?: 0, cutoutInsets?.right ?: 0).coerceAtLeast(16)
    val verticalMargin = (cutoutInsets?.top ?: 0).coerceAtLeast(16)
    PlaybackSession.setOptionString("osd-margin-x", horizontalMargin.toString())
    PlaybackSession.setOptionString("osd-margin-y", verticalMargin.toString())
  }

  @Suppress("ReturnCount", "DEPRECATION")
  fun onKey(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) {
      return false
    }

    var mapped = KeyMapping[event.keyCode]
    if (mapped == null) {
      // Fallback to produced glyph
      if (!event.isPrintingKey) {
        return false
      }

      val ch = event.unicodeChar
      if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0) {
        return false // dead key
      }
      mapped = ch.toChar().toString()
    }

    if (event.repeatCount > 0) {
      return true
    }

    val mod: MutableList<String> = mutableListOf()
    event.isShiftPressed && mod.add("shift")
    event.isCtrlPressed && mod.add("ctrl")
    event.isAltPressed && mod.add("alt")
    event.isMetaPressed && mod.add("meta")

    val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
    mod.add(mapped)
    PlaybackSession.command(action, mod.joinToString("+"))

    return true
  }

  override fun surfaceChanged(
    holder: android.view.SurfaceHolder,
    format: Int,
    width: Int,
    height: Int,
  ) {
    PlaybackSession.resizeSurface(width, height, owner = this)
    applyFrameRate()
  }

  override fun surfaceCreated(holder: android.view.SurfaceHolder) {
    if (!surfaceBindingEnabled) return
    isSurfaceReady =
      PlaybackSession.bindSurface(holder.surface, width, height, this, ownerIsActive = { surfaceBindingEnabled })
    // vo=mediacodec_embed needs both windows attached when the video output opens.
    syncOsdSurfaceAttachment()
    // The OSD window may have been created before this video surface (a real SurfaceView creation
    // race on Android — osd_surface is declared after player in the layout). In that case the OSD
    // callback already re-opened the VO while the video surface was still absent, and the
    // syncOsdSurfaceAttachment() call above short-circuits on osdSurfaceAttached. Re-open the embed
    // VO here, now that the video surface is bound, so it picks up BOTH windows. This mirrors
    // BaseMPVView.surfaceCreated, which always calls reopenVo() after the surface (re)binds.
    if (isMediaCodecEmbedActive()) {
      recoverVideoOutputIfNeeded()
      // Belt-and-suspenders: if the OSD window showed up even later (or the file finished loading
      // after the surface bound), re-check once more shortly after playback is expected to be up.
      postDelayed({ recoverVideoOutputIfNeeded() }, 1000)
    }
    registerEmbedRelayoutListener()
    applyFrameRate()
    post {
      if (isSurfaceReady && holder.surface.isValid) {
        onSurfaceReady?.invoke()
      }
    }
  }

  override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
    isSurfaceReady = false
    unregisterEmbedRelayoutListener()
    PlaybackSession.unbindSurface(this)
  }

  private fun applyFrameRate() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      val fps = PlaybackSession.getPropertyDouble("container-fps") ?: 0.0
      if (fps > 0.0 && holder?.surface?.isValid == true) {
        try {
          holder.surface.setFrameRate(
            fps.toFloat(),
            android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
          )
        } catch (e: Exception) {
          Log.e(TAG, "Failed to set frame rate on surface", e)
        }
      }
    }
  }

  private val observedProps =
    mapOf(
      "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "cache-buffering-state" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "demuxer-cache-duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "network" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "video-params/aspect" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "video-params/w" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "video-params/h" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "container-fps" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "user-data/mpvrx/show_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/toggle_ui" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/show_panel" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/set_button_title" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/reset_button_title" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/toggle_button" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/seek_by" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/seek_to" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/seek_by_with_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/seek_to_with_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/software_keyboard" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // Curl bridge: scripts write a JSON request here; response is written to curl_response
      "user-data/mpvrx/curl_request" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // curl_response is written by the bridge; scripts observe this property for results
      "user-data/mpvrx/curl_response" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // Track console visibility state
      "user-data/mpv/console/open" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "sub-text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "sub-scale" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
    )

  private fun setupAudioOptions() {
    // Let mpv resolve the common case during demuxer initialization. TrackSelector still applies
    // title-based commentary/description filtering after load when mpv's choice needs correction.
    PlaybackSession.setOptionString("alang", audioPreferences.preferredLanguages.get().toMpvLanguageList())
    PlaybackSession.setOptionString("audio-display", "embedded-first")
    PlaybackSession.setOptionString("audio-delay", (audioPreferences.defaultAudioDelay.get() / 1000.0).toString())
    PlaybackSession.setOptionString("audio-pitch-correction", audioPreferences.audioPitchCorrection.get().toString())
    PlaybackSession.setOptionString("volume-max", (audioPreferences.volumeBoostCap.get() + 100).toString())
    // Prevent automatic volume normalization when downmixing multi-channel audio
    PlaybackSession.setOptionString("audio-normalize-downmix", "no")
  }

  // Setup
  private fun setupSubtitlesOptions() {
    // Resolve preferred languages before packet reads begin, but preserve the global subtitle-off
    // preference. TrackSelector remains responsible for title/forced/hearing-impaired filtering.
    val preferredSubtitleLanguages =
      subtitlesPreferences.preferredLanguages.get().toMpvLanguageList()
        .takeIf { subtitlesPreferences.autoEnableSubtitles.get() }
        .orEmpty()
    PlaybackSession.setOptionString("slang", preferredSubtitleLanguages)
    PlaybackSession.setOptionString("sub-auto", "no")
    PlaybackSession.setOptionString("sub-file-paths", "")
    PlaybackSession.setOptionString("subs-fallback", "no")

    val fontsDirPath = "${context.filesDir.path}/fonts/"
    PlaybackSession.setOptionString("sub-fonts-dir", fontsDirPath)
    // Auto-detect subtitle encoding
    PlaybackSession.setOptionString("sub-codepage", "auto")
    // Allow embedded fonts from MKV/MP4 containers
    PlaybackSession.setOptionString("embeddedfonts", "yes")
    // Auto-detect font provider (system fonts, embedded fonts, etc.)
    PlaybackSession.setOptionString("sub-font-provider", "auto")

    // Delay and speed for both primary and secondary
    val subDelay = (subtitlesPreferences.defaultSubDelay.get() / 1000.0).toString()
    val subSpeed = subtitlesPreferences.defaultSubSpeed.get().toString()
    PlaybackSession.setOptionString("sub-delay", subDelay)
    PlaybackSession.setOptionString("sub-speed", subSpeed)
    PlaybackSession.setOptionString("secondary-sub-delay", subDelay)
    PlaybackSession.setOptionString("secondary-sub-speed", subSpeed)

    val preferredFont = subtitlesPreferences.font.get().ifBlank { DEFAULT_SUBTITLE_FONT_FAMILY }
    PlaybackSession.setOptionString("sub-font", preferredFont)
    PlaybackSession.setOptionString("secondary-sub-font", preferredFont)

    if (subtitlesPreferences.overrideAssSubs.get()) {
      PlaybackSession.setOptionString("sub-ass-override", "force")
      PlaybackSession.setOptionString("sub-ass-justify", "yes")
      PlaybackSession.setOptionString("secondary-sub-ass-override", "force")
    } else {
      PlaybackSession.setOptionString("sub-ass-override", "no")
      PlaybackSession.setOptionString("secondary-sub-ass-override", "no")
    }

    // Typography and styling for both primary and secondary
    val fontSize = subtitlesPreferences.fontSize.get().toString()
    val bold = if (subtitlesPreferences.bold.get()) "yes" else "no"
    val italic = if (subtitlesPreferences.italic.get()) "yes" else "no"
    val justify = subtitlesPreferences.justification.get().value
    val textColor = subtitlesPreferences.textColor.get().toColorHexString()
    val backgroundColor = subtitlesPreferences.backgroundColor.get().toColorHexString()
    val borderColor = subtitlesPreferences.borderColor.get().toColorHexString()
    val shadowColor = subtitlesPreferences.shadowColor.get().toColorHexString()
    val borderSize = subtitlesPreferences.borderSize.get().toString()
    val borderStyle = subtitlesPreferences.borderStyle.get().value
    val shadowOffset = subtitlesPreferences.shadowOffset.get().toString()
    val subPos = clampSubtitlePosition(subtitlesPreferences.subPos.get())
    val w =
      width.takeIf { it > 0 }?.toFloat() ?: context.resources.displayMetrics.widthPixels
        .toFloat()
    val h =
      height.takeIf { it > 0 }?.toFloat() ?: context.resources.displayMetrics.heightPixels
        .toFloat()
    val secondarySubPos = calculateSecondarySubtitlePosition(subPos, w, h)
    val subScale = subtitlesPreferences.subScale.get().toString()

    val scaleByWindow = if (subtitlesPreferences.scaleByWindow.get()) "yes" else "no"
    val blendMode =
      if (subtitlesPreferences.blendSubtitlesWithVideo.get() &&
        playerPreferences.isAmbientEnabled.get()
      ) {
        "video"
      } else {
        "no"
      }
    PlaybackSession.setOptionString("blend-subtitles", blendMode)

    PlaybackSession.setOptionString("sub-font-size", fontSize)
    for ((prefix, pos) in listOf("sub-" to subPos.toString(), "secondary-sub-" to secondarySubPos.toString())) {
      PlaybackSession.setOptionString("${prefix}bold", bold)
      PlaybackSession.setOptionString("${prefix}italic", italic)
      PlaybackSession.setOptionString("${prefix}justify", justify)
      PlaybackSession.setOptionString("${prefix}color", textColor)
      PlaybackSession.setOptionString("${prefix}back-color", backgroundColor)
      PlaybackSession.setOptionString("${prefix}border-color", borderColor)
      PlaybackSession.setOptionString("${prefix}shadow-color", shadowColor)
      PlaybackSession.setOptionString("${prefix}border-size", borderSize)
      PlaybackSession.setOptionString("${prefix}border-style", borderStyle)
      PlaybackSession.setOptionString("${prefix}shadow-offset", shadowOffset)
      PlaybackSession.setOptionString("${prefix}scale", subScale)
      PlaybackSession.setOptionString("${prefix}pos", pos)
      PlaybackSession.setOptionString("${prefix}scale-by-window", scaleByWindow)
      PlaybackSession.setOptionString("${prefix}use-margins", scaleByWindow)
    }
  }

  fun applyAnime4KShaders() {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) return
    applyAnime4KShaders(
      activeVo = PlaybackSession.getPropertyString("vo") ?: "",
      activeGpuApi = PlaybackSession.getPropertyString("gpu-api") ?: "",
    )
  }

  /**
   * Copies bundled hdr-toys GLSL shaders to filesDir on first use, then appends
   * the chosen profile's shader chain to mpv's glsl-shaders list.
   * Safe to call on every init — clears previous hdr-toys shaders before re-applying.
   */
  fun applyHdrToysMode(
    mode: HdrScreenMode,
    pipelineReady: Boolean,
  ) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) return
    val profile = mode.hdrToysProfile
    if (!pipelineReady || profile == null) {
      hdrToysManager.clear()
      return
    }
    if (!hdrToysManager.apply(profile)) {
      Log.w(TAG, "Skipping HDR Toys mode — bundled shaders unavailable: ${mode.name}")
    }
  }

  private fun applyAnime4KShaders(
    activeVo: String,
    activeGpuApi: String,
  ) {
    runCatching {
      val isGpuNext = activeVo == "gpu-next"
      val useVulkan = activeGpuApi == "vulkan"

      // ── Standard Anime4K (requires master switch) ─────────────────────────
      val enabled = decoderPreferences.enableAnime4K.get()
      if (!enabled) {
        clearAnime4KShaders()
        return
      }

      // Standard mode needs legacy gpu OR gpu-next+Vulkan
      if (isGpuNext && !useVulkan) {
        Log.w(TAG, "Skipping standard Anime4K — gpu-next without Vulkan")
        return
      }

      val modeStr = decoderPreferences.anime4kMode.get()
      if (modeStr == "OFF") {
        clearAnime4KShaders()
        return
      }

      // Parse user's selected mode
      val mode =
        try {
          Anime4KManager.Mode.valueOf(modeStr)
        } catch (e: IllegalArgumentException) {
          Anime4KManager.Mode.OFF
        }

      val selection =
        selectRuntimeStableAnime4K(
          mode = mode,
          quality = decoderPreferences.anime4kQuality.get(),
          context = context,
          enableIn4k = decoderPreferences.anime4kIn4k.get(),
        )
      selection.reason?.let { reason ->
        Log.i(TAG, "Anime4K thermal guard: $reason")
      }
      if (selection.mode == Anime4KManager.Mode.OFF) {
        clearAnime4KShaders()
        return
      }

      anime4kManager.setPostFilters(
        darken = decoderPreferences.anime4kDarken.get(),
        thin = decoderPreferences.anime4kThin.get(),
        deblur = decoderPreferences.anime4kDeblur.get(),
      )
      if (applyAnime4KShaderChain(anime4kManager, selection.mode, selection.quality)) {
        applyAnime4KStabilityOptions(useVulkan = useVulkan)
      } else {
        Log.w(
          TAG,
          "Anime4K shader chain is empty for mode=${selection.mode} quality=${selection.quality}",
        )
      }
    }.onFailure {
      Log.w(TAG, "Failed to apply Anime4K shaders", it)
    }
  }

  private fun shouldUseVulkan(ignoreForcedOpenGlFallback: Boolean = false): Boolean {
    val canUseVulkan =
      RendererBackendPolicy.canUseVulkan(
        buildIncludesVulkan = BuildConfig.MPV_SUPPORTS_VULKAN,
        deviceSupportsVulkan = VulkanCapabilities.isDeviceSupported(context),
        userEnabledVulkan = decoderPreferences.useVulkan.get(),
        forceOpenGlFallback = forceOpenGlFallback && !ignoreForcedOpenGlFallback,
      )
    if (decoderPreferences.useVulkan.get() && !canUseVulkan) {
      Log.w(TAG, "Vulkan is unavailable for this build or device. Forcing OpenGL.")
    }
    return canUseVulkan
  }

  private fun preferredHwdecMode(usesVulkan: Boolean): String =
    RendererBackendPolicy.preferredHwdecMode(
      hardwareDecodingEnabled = decoderPreferences.tryHWDecoding.get(),
      usesVulkan = usesVulkan,
      buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
    )

  private fun selectRenderBackend(ignoreForcedOpenGlFallback: Boolean = false): RenderBackendSelection {
    val anime4kEnabled =
      decoderPreferences.enableAnime4K.get() &&
        (decoderPreferences.anime4kMode.get() != "OFF")
    val gpuNextEnabled = decoderPreferences.gpuNext.get()
    val vulkanEnabled = shouldUseVulkan(ignoreForcedOpenGlFallback)

    if (anime4kEnabled && gpuNextEnabled && !vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu",
        gpuApi = "opengl",
        gpuContext = "android",
        reason = "Anime4K with gpu-next but without Vulkan is unsupported: fallback to legacy gpu/opengl",
      )
    }

    if (gpuNextEnabled && vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu-next",
        gpuApi = "vulkan",
        gpuContext = "androidvk",
        reason =
          if (anime4kEnabled) {
            "Anime4K active with gpu-next and Vulkan enabled: keep gpu-next/vulkan path"
          } else {
            "gpu-next and Vulkan enabled: use gpu-next/vulkan"
          },
      )
    }

    if (gpuNextEnabled) {
      return RenderBackendSelection(
        vo = "gpu-next",
        gpuApi = "opengl",
        gpuContext = "android",
        reason = "gpu-next enabled without Vulkan: use gpu-next/opengl",
      )
    }

    if (vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu",
        gpuApi = "vulkan",
        gpuContext = "androidvk",
        reason =
          if (anime4kEnabled) {
            "Anime4K active with legacy gpu and Vulkan enabled: use gpu/vulkan"
          } else {
            "Vulkan enabled with legacy gpu selected: use gpu/vulkan"
          },
      )
    }

    return RenderBackendSelection(
      vo = "gpu",
      gpuApi = "opengl",
      gpuContext = "android",
      reason =
        if (anime4kEnabled) {
          "Anime4K active with legacy gpu selected: use gpu/opengl"
        } else {
          "gpu-next and Vulkan disabled: use gpu/opengl"
        },
    )
  }
}

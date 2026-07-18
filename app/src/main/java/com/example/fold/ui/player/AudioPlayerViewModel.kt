package com.example.fold.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.fold.service.MusicEventBus
import com.example.fold.service.MusicEvent
import com.example.fold.service.MusicNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class MusicUiState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val currentIndex: Int = 0,
    val playlistSize: Int = 1,
    val loopMode: Int = 0,          // 0=顺序, 1=单曲, 2=随机
    val albumArt: Bitmap? = null,
    val lyrics: List<Pair<Long, String>> = emptyList(),
    val currentLyricIndex: Int = -1,
    val showLyrics: Boolean = false,
    val sleepMinutes: Int = 0,
    val sleepRemaining: Int = 0,    // >0 秒, -1=等待歌曲结束, 0=关闭
    val sleepFinishSong: Boolean = false,
    val audioSessionId: Int = -1,
    val playlistPaths: List<String> = emptyList(),
    val initialized: Boolean = false,
    val isImmersive: Boolean = false,
)

class AudioPlayerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AudioPlayerVM"
    }

    private val _state = MutableStateFlow(MusicUiState())
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    private val context = application
    lateinit var exoPlayer: ExoPlayer
        private set

    private var resolvedPlaylist: List<String> = emptyList()
    private val audioExtensions = setOf("mp3","wav","flac","aac","ogg","wma","m4a","opus","ape")
    private var shuffleQueue = mutableListOf<Int>()
    private var shuffleIndex = 0
    private var playerListener: Player.Listener? = null

    fun init(filePath: String, playlist: List<String>) {
        com.example.fold.util.FoldLogger.i(TAG, "=== init START === filePath=$filePath, lastFilePath=${MusicPlayerHolder.lastFilePath}, isActive=${MusicPlayerHolder.isActive()}, playlistSize=${MusicPlayerHolder.playlist.size}, mediaIdx=${MusicPlayerHolder.exoPlayer?.currentMediaItemIndex}, argPlaylist=${playlist.size}")

        // 如果正在播放且播放器还在，检查是否是同一首歌
        if (MusicPlayerHolder.isActive() && MusicPlayerHolder.playlist.isNotEmpty()) {
            exoPlayer = MusicPlayerHolder.exoPlayer!!
            resolvedPlaylist = MusicPlayerHolder.playlist
            val currentIdx = exoPlayer.currentMediaItemIndex
            val currentPath = resolvedPlaylist.getOrElse(currentIdx) { filePath }

            // 如果点击的是新歌（不在当前播放位置），需要切换
            if (filePath != currentPath) {
                val targetIdx = resolvedPlaylist.indexOf(filePath)
                if (targetIdx >= 0) {
                    // 新歌在当前播放列表中，直接切过去
                    com.example.fold.util.FoldLogger.i(TAG, "  Song in playlist, seeking to idx=$targetIdx")
                    exoPlayer.seekToDefaultPosition(targetIdx)
                } else {
                    // 新歌不在播放列表中，重新播放
                    com.example.fold.util.FoldLogger.i(TAG, "  Song not in playlist, starting new playback")
                    MusicPlayerHolder.lastFilePath = filePath
                    resolvedPlaylist = if (playlist.isNotEmpty()) playlist
                    else {
                        val dir = File(filePath).parentFile
                        dir?.listFiles()
                            ?.filter { it.isFile && it.extension.lowercase() in audioExtensions }
                            ?.sortedBy { it.name }
                            ?.map { it.absolutePath }
                            ?: listOf(filePath)
                    }
                    val initialIndex = resolvedPlaylist.indexOf(filePath).coerceAtLeast(0)
                    MusicPlayerHolder.loadPlaylist(context, resolvedPlaylist, initialIndex)
                }
                // 更新 UI 到新歌
                MusicPlayerHolder.lastFilePath = filePath
                val newIdx = if (targetIdx >= 0) targetIdx else resolvedPlaylist.indexOf(filePath).coerceAtLeast(0)
                val newPath = resolvedPlaylist.getOrElse(newIdx) { filePath }
                _state.value = _state.value.copy(
                    title = newPath.substringAfterLast('/').substringBeforeLast('.'),
                    currentIndex = newIdx,
                    playlistSize = resolvedPlaylist.size,
                    playlistPaths = resolvedPlaylist,
                    audioSessionId = exoPlayer.audioSessionId,
                    isPlaying = exoPlayer.isPlaying,
                    duration = 0L,
                    currentPosition = 0L,
                    initialized = true
                )
                loadAlbumArt(newPath)
                loadLyrics(newPath)
                attachListeners()
                startPolling()
                return
            }

            // 同一首歌，复用播放器
            com.example.fold.util.FoldLogger.i(TAG, "  Reusing player. currentIdx=$currentIdx, currentPath=$currentPath")
            _state.value = _state.value.copy(
                title = currentPath.substringAfterLast('/').substringBeforeLast('.'),
                currentIndex = currentIdx,
                playlistSize = resolvedPlaylist.size,
                playlistPaths = resolvedPlaylist,
                audioSessionId = exoPlayer.audioSessionId,
                isPlaying = exoPlayer.isPlaying,
                duration = exoPlayer.duration.coerceAtLeast(0),
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0),
                initialized = true
            )
            com.example.fold.util.FoldLogger.i(TAG, "  → State updated: title=${_state.value.title}, idx=${_state.value.currentIndex}, playing=${_state.value.isPlaying}")
            loadAlbumArt(currentPath)
            loadLyrics(currentPath)
            attachListeners()
            startPolling()
            return
        }

        // 新播放
        MusicPlayerHolder.lastFilePath = filePath
        com.example.fold.util.FoldLogger.i(TAG, "  New playback, lastFilePath=$filePath")

        resolvedPlaylist = if (playlist.isNotEmpty()) playlist
        else {
            val dir = File(filePath).parentFile
            dir?.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in audioExtensions }
                ?.sortedBy { it.name }
                ?.map { it.absolutePath }
                ?: listOf(filePath)
        }

        val initialIndex = resolvedPlaylist.indexOf(filePath).coerceAtLeast(0)

        // 使用全局 Holder，ExoPlayer 不随 ViewModel 销毁
        exoPlayer = MusicPlayerHolder.getOrCreate(context)
        MusicPlayerHolder.loadPlaylist(context, resolvedPlaylist, initialIndex)

        // 初始加载
        val initPath = resolvedPlaylist.getOrNull(initialIndex) ?: filePath
        _state.value = _state.value.copy(
            title = initPath.substringAfterLast('/').substringBeforeLast('.'),
            artist = initPath.substringAfterLast('/').substringBeforeLast('.'),
            currentIndex = initialIndex,
            playlistSize = resolvedPlaylist.size,
            playlistPaths = resolvedPlaylist,
            audioSessionId = exoPlayer.audioSessionId,
            initialized = true
        )
        // 同步悬浮小窗状态
        MiniPlayerState.update(
            title = initPath.substringAfterLast('/').substringBeforeLast('.'),
            isPlaying = exoPlayer.isPlaying,
            albumArt = null,
            filePath = initPath,
        )
        loadAlbumArt(initPath)
        loadLyrics(initPath)

        attachListeners()
        startPolling()
    }

    private fun attachListeners() {
        // 移除旧监听器
        playerListener?.let { exoPlayer.removeListener(it) }

        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _state.value = _state.value.copy(isPlaying = playing)
                MiniPlayerState.updatePlaying(playing)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.value = _state.value.copy(duration = exoPlayer.duration)
                }
                if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val idx = exoPlayer.currentMediaItemIndex
                val path = resolvedPlaylist.getOrNull(idx) ?: ""
                val newTitle = path.substringAfterLast('/').substringBeforeLast('.')
                _state.value = _state.value.copy(
                    currentIndex = idx,
                    title = newTitle,
                    duration = exoPlayer.duration.coerceAtLeast(0),
                    currentPosition = 0,
                    currentLyricIndex = -1
                )
                // 同步悬浮小窗
                MiniPlayerState.update(
                    title = newTitle,
                    isPlaying = _state.value.isPlaying,
                    albumArt = null,
                    filePath = path,
                )
                loadAlbumArt(path)
                loadLyrics(path)
            }
        }
        exoPlayer.addListener(playerListener!!)
    }

    private var lastNotifTitle = ""
    private var lastNotifIsPlaying = false
    private var lastNotifPositionMs = -1L

    private fun startPolling() {
        // 歌词索引轮询（500ms，仅歌词行变化才更新 state → 只触发歌词区重绘）
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (MusicPlayerHolder.isActive() && _state.value.lyrics.isNotEmpty()) {
                    updateLyricIndex(exoPlayer.currentPosition)
                }
            }
        }

        // 通知状态推送（1 秒一次，仅状态有变化才调用 MediaSession）
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (MusicPlayerHolder.isActive()) pushNotification()
            }
        }

        // 监听通知栏按钮
        viewModelScope.launch {
            MusicEventBus.events.collect { event ->
                if (!MusicPlayerHolder.isActive()) return@collect
                when (event) {
                    MusicEvent.PREV -> if (exoPlayer.hasPreviousMediaItem()) exoPlayer.seekToPrevious() else exoPlayer.seekTo(0)
                    MusicEvent.PAUSE -> exoPlayer.pause()
                    MusicEvent.RESUME -> exoPlayer.play()
                    MusicEvent.NEXT -> if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNext() else exoPlayer.seekTo(0)
                    MusicEvent.STOP -> {
                        exoPlayer.pause()
                        exoPlayer.seekTo(0)
                        MusicPlayerHolder.release(context)
                    }
                }
            }
        }
    }

    // ===== 播放控制 =====

    /** 检查 player 是否被外部销毁（如 USB 拔出），自动重建 */
    private fun ensurePlayer() {
        if (!MusicPlayerHolder.isActive()) {
            android.util.Log.w("AudioPlayer", "Player was destroyed, recreating")
            exoPlayer = MusicPlayerHolder.getOrCreate(context)
            MusicPlayerHolder.loadPlaylist(context, resolvedPlaylist, _state.value.currentIndex)
            attachListeners()
        }
    }

    fun togglePlay() { ensurePlayer(); if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }
    fun prev() { ensurePlayer(); val idx = exoPlayer.currentMediaItemIndex; if (idx > 0) exoPlayer.seekTo(idx - 1, 0) else exoPlayer.seekTo(0) }
    fun next() { ensurePlayer(); if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNext() else exoPlayer.seekTo(0) }
    fun seekTo(position: Long) { ensurePlayer(); exoPlayer.seekTo(position) }
    fun seekToIndex(index: Int) { ensurePlayer(); exoPlayer.seekTo(index, 0); exoPlayer.play() }

    fun cycleLoopMode() {
        val newMode = (_state.value.loopMode + 1) % 3
        exoPlayer.repeatMode = if (newMode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(loopMode = newMode)
        if (newMode == 2) generateShuffleQueue()
    }

    /** Fisher-Yates 洗牌，保证每首歌播一次、不连续重复 */
    private fun generateShuffleQueue() {
        val size = resolvedPlaylist.size
        if (size <= 1) return
        shuffleQueue = (0 until size).toMutableList()
        // 确保队首不是当前歌曲
        val current = _state.value.currentIndex
        if (shuffleQueue.first() == current && size > 1) {
            val swapWith = (1 until size).random()
            shuffleQueue[0] = shuffleQueue[swapWith].also { shuffleQueue[swapWith] = shuffleQueue[0] }
        }
        shuffleQueue.shuffle()
        // 再次确保队首不是当前歌曲
        if (shuffleQueue.first() == current && size > 1) {
            val swapWith = (1 until size).random()
            shuffleQueue[0] = shuffleQueue[swapWith].also { shuffleQueue[swapWith] = shuffleQueue[0] }
        }
        shuffleIndex = 0
        android.util.Log.d("AudioPlayer", "shuffle queue: $shuffleQueue")
    }

    fun toggleLyrics() {
        if (_state.value.lyrics.isNotEmpty()) {
            _state.value = _state.value.copy(showLyrics = !_state.value.showLyrics)
        }
    }

    fun toggleImmersive() {
        val new = !_state.value.isImmersive
        android.util.Log.d("AudioPlayer", "toggleImmersive: ${_state.value.isImmersive} -> $new")
        _state.value = _state.value.copy(isImmersive = new)
    }

    // ===== 睡眠定时 =====

    private var sleepJob: kotlinx.coroutines.Job? = null

    fun setSleep(minutes: Int, finishSong: Boolean = false) {
        // 取消旧的倒计时
        sleepJob?.cancel()
        _state.value = _state.value.copy(
            sleepMinutes = minutes,
            sleepRemaining = minutes * 60,  // 转成秒
            sleepFinishSong = finishSong
        )
        sleepJob = viewModelScope.launch {
            var remaining = minutes * 60  // 秒
            while (remaining > 0 && isActive) {
                delay(1000)  // 每秒更新
                remaining--
                _state.value = _state.value.copy(sleepRemaining = remaining)
            }
            // 倒计时结束
            if (isActive) {
                if (_state.value.sleepFinishSong) {
                    _state.value = _state.value.copy(sleepRemaining = -1)
                } else {
                    exoPlayer.pause()
                    _state.value = _state.value.copy(sleepMinutes = 0, sleepRemaining = 0)
                }
            }
        }
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        _state.value = _state.value.copy(sleepMinutes = 0, sleepRemaining = 0, sleepFinishSong = false)
    }

    private fun handleTrackEnded() {
        if (_state.value.sleepRemaining == -1) {
            exoPlayer.seekTo(0); exoPlayer.pause()
            _state.value = _state.value.copy(sleepMinutes = 0, sleepRemaining = 0)
            return
        }
        when (_state.value.loopMode) {
            1 -> { exoPlayer.seekTo(0); exoPlayer.play() }
            2 -> {
                // Fisher-Yates 队列：播完一首取下一首，队列耗尽则重新洗牌
                if (shuffleQueue.isEmpty() || shuffleIndex >= shuffleQueue.size) {
                    generateShuffleQueue()
                }
                val nextIdx = shuffleQueue[shuffleIndex++]
                exoPlayer.seekTo(nextIdx, 0)
                exoPlayer.play()
            }
        }
    }

    // ===== 歌词 =====

    private fun loadLyrics(audioPath: String) {
        android.util.Log.d("AudioPlayer", "loadLyrics: path=$audioPath")

        // 先尝试从音频文件元数据读取嵌入歌词
        val embedded = readEmbeddedLyrics(audioPath)
        if (embedded != null && embedded.isNotBlank()) {
            android.util.Log.d("AudioPlayer", "loadLyrics: found embedded lyrics, len=${embedded.length}")
            parseAndSetLyrics(embedded)
            return
        }

        // 再尝试独立 LRC 文件
        val audioFile = File(audioPath)
        val baseName = audioFile.nameWithoutExtension
        val parentDir = audioFile.parentFile
        val allLrc = parentDir?.listFiles()?.filter { it.extension.equals("lrc", true) } ?: emptyList()
        android.util.Log.d("AudioPlayer", "loadLyrics: no embedded, dir lrc files=${allLrc.map { it.name }}")

        var lrcFile = File(parentDir, "$baseName.lrc").takeIf { it.exists() && it.length() > 0 }
        if (lrcFile == null) {
            lrcFile = allLrc.firstOrNull { lrc ->
                baseName.startsWith(lrc.nameWithoutExtension, ignoreCase = true)
            }
        }
        if (lrcFile == null) {
            lrcFile = allLrc.firstOrNull { lrc ->
                lrc.nameWithoutExtension.contains(baseName, ignoreCase = true)
            }
        }
        if (lrcFile == null) {
            val stripped = baseName.replace(Regex("""[-_]\d{4,}$"""), "")
            if (stripped != baseName) {
                lrcFile = allLrc.firstOrNull { lrc ->
                    lrc.nameWithoutExtension.equals(stripped, ignoreCase = true) ||
                    stripped.startsWith(lrc.nameWithoutExtension, ignoreCase = true)
                }
            }
        }

        android.util.Log.d("AudioPlayer", "loadLyrics: lrc file=${lrcFile?.name}")

        if (lrcFile == null) {
            _state.value = _state.value.copy(lyrics = emptyList(), currentLyricIndex = -1, showLyrics = false)
            return
        }

        try {
            val bytes = lrcFile.readBytes()
            val text = decodeText(bytes)
            parseAndSetLyrics(text)
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "loadLyrics lrc file failed", e)
            _state.value = _state.value.copy(lyrics = emptyList())
        }
    }

    /** 从音频文件元数据提取嵌入歌词 */
    private fun readEmbeddedLyrics(audioPath: String): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(audioPath)

            // METADATA_KEY_LYRICS = 26 (API 31+)
            var lyrics = try { retriever.extractMetadata(26) } catch (_: Exception) { null }

            // 如果没有标准歌词字段，尝试提取 Vorbis Comments（FLAC）
            if (lyrics.isNullOrBlank()) {
                lyrics = extractFlacLyrics(audioPath)
            }

            retriever.release()
            lyrics
        } catch (e: Exception) {
            android.util.Log.w("AudioPlayer", "readEmbeddedLyrics failed", e)
            null
        }
    }

    /** 解析 FLAC 文件的 Vorbis Comments 寻找 LYRICS 字段 */
    private fun extractFlacLyrics(path: String): String? {
        return try {
            val file = File(path)
            if (file.length() < 8) return null
            val raf = java.io.RandomAccessFile(file, "r")
            val header = ByteArray(4)
            raf.readFully(header)
            if (String(header) != "fLaC") { raf.close(); return null }

            // 遍历 metadata blocks
            while (raf.filePointer < file.length()) {
                val typeAndLen = ByteArray(4)
                if (raf.read(typeAndLen) < 4) break
                val isLast = (typeAndLen[0].toInt() and 0x80) != 0
                val blockType = typeAndLen[0].toInt() and 0x7F
                val blockLen = ((typeAndLen[1].toInt() and 0xFF) shl 16) or
                               ((typeAndLen[2].toInt() and 0xFF) shl 8) or
                               (typeAndLen[3].toInt() and 0xFF)

                if (blockType == 4) {
                    // Vorbis Comments block
                    val data = ByteArray(blockLen)
                    raf.readFully(data)
                    val result = parseVorbisLyrics(data)
                    raf.close()
                    return result
                } else {
                    raf.skipBytes(blockLen)
                }

                if (isLast) break
            }
            raf.close()
            null
        } catch (e: Exception) {
            android.util.Log.w("AudioPlayer", "extractFlacLyrics failed", e)
            null
        }
    }

    /** 从 Vorbis Comments 数据中提取 LYRICS / SYNCEDLYRICS */
    private fun parseVorbisLyrics(data: ByteArray): String? {
        try {
            var pos = 0
            // vendor string
            if (data.size < 4) return null
            val vendorLen = (data[pos].toInt() and 0xFF) or
                            ((data[pos+1].toInt() and 0xFF) shl 8) or
                            ((data[pos+2].toInt() and 0xFF) shl 16) or
                            ((data[pos+3].toInt() and 0xFF) shl 24)
            pos += 4 + vendorLen

            // comment count
            if (pos + 4 > data.size) return null
            val count = (data[pos].toInt() and 0xFF) or
                        ((data[pos+1].toInt() and 0xFF) shl 8) or
                        ((data[pos+2].toInt() and 0xFF) shl 16) or
                        ((data[pos+3].toInt() and 0xFF) shl 24)
            pos += 4

            for (i in 0 until count) {
                if (pos + 4 > data.size) break
                val len = (data[pos].toInt() and 0xFF) or
                          ((data[pos+1].toInt() and 0xFF) shl 8) or
                          ((data[pos+2].toInt() and 0xFF) shl 16) or
                          ((data[pos+3].toInt() and 0xFF) shl 24)
                pos += 4
                if (pos + len > data.size) break
                val entry = String(data, pos, len, Charsets.UTF_8)
                pos += len

                val upper = entry.uppercase()
                if (upper.startsWith("LYRICS=") || upper.startsWith("SYNCEDLYRICS=")) {
                    return entry.substringAfter('=')
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** 解码字节（UTF-8 / GBK） */
    private fun decodeText(bytes: ByteArray): String {
        return try {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (utf8.contains('�')) String(bytes, charset("GBK")) else utf8
        } catch (_: Exception) { String(bytes, charset("GBK")) }
    }

    /** 解析歌词文本（LRC 格式时间标签 + 纯文本） */
    private fun parseAndSetLyrics(text: String) {
        val timeRegex = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{2,3})]|\[(\d{1,3}):(\d{2})]""")
        val parsed = mutableListOf<Pair<Long, String>>()
        var hasTimedLyrics = false

        text.lines().forEach { line ->
            val times = mutableListOf<Long>()
            timeRegex.findAll(line).forEach { m ->
                hasTimedLyrics = true
                if (m.groupValues[1].isNotEmpty()) {
                    val ms = m.groupValues[3].toLong().let { if (it < 100) it * 10 else it }
                    times.add(m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + ms)
                } else if (m.groupValues[4].isNotEmpty()) {
                    times.add(m.groupValues[4].toLong() * 60000 + m.groupValues[5].toLong() * 1000)
                }
            }

            if (hasTimedLyrics) {
                // 带时间标签的 LRC 格式
                if (times.isEmpty()) return@forEach
                val lastBracket = line.lastIndexOf(']')
                val content = if (lastBracket in 0 until line.length - 1) line.substring(lastBracket + 1).trim() else ""
                if (content.isNotEmpty()) times.forEach { t -> parsed.add(t to content) }
            } else {
                // 纯文本歌词（无时间标签），每行分配等间隔
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) parsed.add(-1L to trimmed)
            }
        }

        // 如果是纯文本歌词（无时间标签），按行均分时间
        if (!hasTimedLyrics && parsed.isNotEmpty()) {
            val duration = _state.value.duration.coerceAtLeast(1000)
            val interval = duration / parsed.size
            parsed.forEachIndexed { i, pair -> parsed[i] = (i * interval) to pair.second }
        }

        _state.value = _state.value.copy(lyrics = parsed.sortedBy { it.first }, currentLyricIndex = -1)
        android.util.Log.d("AudioPlayer", "loadLyrics: parsed ${parsed.size} lines, timed=$hasTimedLyrics")
    }

    fun updateLyricIndex(position: Long) {
        val lyrics = _state.value.lyrics
        if (lyrics.isEmpty()) return
        val idx = lyrics.indexOfLast { it.first <= position }
        if (idx != _state.value.currentLyricIndex) {
            _state.value = _state.value.copy(currentLyricIndex = idx)
        }
    }

    // ===== 封面 =====

    private fun loadAlbumArt(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val art = retriever.embeddedPicture
                val bitmap = if (art != null) BitmapFactory.decodeByteArray(art, 0, art.size) else null
                retriever.release()
                _state.value = _state.value.copy(albumArt = bitmap)
                // 同步悬浮小窗封面
                MiniPlayerState.update(
                    title = _state.value.title,
                    isPlaying = _state.value.isPlaying,
                    albumArt = bitmap,
                    filePath = path,
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(albumArt = null)
            }
        }
    }

    // ===== 通知 =====

    private fun pushNotification() {
        val s = _state.value
        // 直接从 ExoPlayer 读位置，不再依赖 state.currentPosition
        val pos = exoPlayer.currentPosition
        val titleChanged = s.title != lastNotifTitle
        val playingChanged = s.isPlaying != lastNotifIsPlaying
        val posChanged = (pos - lastNotifPositionMs).coerceAtLeast(0) > 1000
        if (!titleChanged && !playingChanged && !posChanged) return
        lastNotifTitle = s.title
        lastNotifIsPlaying = s.isPlaying
        lastNotifPositionMs = pos
        MusicNotificationService.updatePlayback(
            title = s.title,
            artist = s.artist,
            albumArt = s.albumArt,
            isPlaying = s.isPlaying,
            positionMs = pos,
            durationMs = s.duration
        )
    }

    // ===== 均衡器 =====

    fun getEqualizer(audioSessionId: Int): Equalizer? {
        return try { Equalizer(0, audioSessionId) } catch (_: Exception) { null }
    }

    override fun onCleared() {
        super.onCleared()
        // 只移除监听器，不释放 ExoPlayer（支持后台播放）
        playerListener?.let { exoPlayer.removeListener(it) }
    }
}
